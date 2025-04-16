package com.imbank.service.impl;

import com.google.gson.Gson;
import com.imbank.exceptions.TransactionServiceException;
import com.imbank.model.ConnectorResponse;
import com.imbank.model.TransactionBody;
import com.imbank.model.TransactionResponse;
import com.imbank.service.TransactionService;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Base64;

@RequiredArgsConstructor
@Slf4j
@Service
public class TransactionServiceImpl implements TransactionService {

    private final RestTemplate restTemplate;
    Gson gson = new Gson();
    private static final String SOAP_URL = "http://192.168.205.126:1880/bcwsgateway/services/BCMpesaRefService?wsdl";

    @Override
    public ConnectorResponse postTransaction(String url, String username, String password, TransactionBody transactionBody) {
        TransactionResponse transactionResponse;
        ConnectorResponse connectorResponse = new ConnectorResponse();

        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        log.info("Posting transaction to URL: {}", url);
        log.debug("TransactionBody: {}", gson.toJson(transactionBody));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Authorization", "Basic " + encodedAuth);
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            HttpEntity<TransactionBody> entity = new HttpEntity<>(transactionBody, headers);
            ResponseEntity<TransactionResponse> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    TransactionResponse.class
            );

            transactionResponse = responseEntity.getBody();
            log.info("Transaction response: {}", gson.toJson(transactionResponse));

            if (transactionResponse.getResultCode().equals("0")) {
                connectorResponse.setAccountnumber(transactionBody.getCustomerid());
                connectorResponse.setContactid(transactionBody.getCustomerid());
                connectorResponse.setResultmessage(transactionResponse.getResultDesc() + " ERP Ref Id " + transactionResponse.getErpRefId());
                connectorResponse.setResultcode(transactionResponse.getResultCode());
                connectorResponse.setResultdesc(transactionResponse.getResultDesc() + " ERP Ref Id " + transactionResponse.getErpRefId());

                String customerName = getMpesaName(transactionBody.getTransactionref());
                connectorResponse.setConsumername(customerName);
            } else {
                connectorResponse.setResultcode(transactionResponse.getResultCode());
                connectorResponse.setResultmessage(transactionResponse.getResultDesc());
            }
            return connectorResponse;
        } catch (Exception e) {
            log.error("Error processing transaction to {}: {}", url, e.getMessage(), e);
            throw new TransactionServiceException("Failed to process transaction: " + e.getMessage());
        }
    }

    public String getMpesaName(String refNumber) {
        log.info("Calling getMpesaName with refNumber: {}", refNumber);
        String soapRequest = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:ser=\"http://www.imbank.com/service\">" +
                "<soapenv:Header/>" +
                "<soapenv:Body>" +
                "<ser:BCMpesaRefNameRequest>" +
                "<Mpesa_Ref>" + refNumber + "</Mpesa_Ref>\r\n"+        "</ser:BCMpesaRefNameRequest>" +
                "</soapenv:Body>" +
                "</soapenv:Envelope>";

        HttpResponse<String> mpesaResponse = null;
        String customerName = "";

        try {
            Unirest.setTimeouts(5000, 10000);

            mpesaResponse = Unirest.post(SOAP_URL)
                    .header("Content-Type", "application/xml")
                    .body(soapRequest)
                    .asString();

            log.info("Mpesa response body: {}", mpesaResponse.getBody());

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(new InputSource(new StringReader(mpesaResponse.getBody())));
            doc.getDocumentElement().normalize();

            log.info("Root element: {}", doc.getDocumentElement().getNodeName());

            Element element = (Element) doc.getElementsByTagName("Cust_Name").item(0);
            customerName = element != null ? element.getTextContent() : "";

            if (customerName.isEmpty()) {
                log.warn("Customer name not found in SOAP response for refNumber: {}", refNumber);
            } else {
                log.info("Customer name retrieved: {}", customerName);
            }

        } catch (UnirestException e) {
            log.error("Unirest error retrieving Mpesa customer name for refNumber {}: {}. Response: {}",
                    refNumber, e.getMessage(), mpesaResponse != null ? mpesaResponse.getBody() : "null", e);
        } catch (Exception e) {
            log.error("Error parsing Mpesa response for refNumber {}: {}. Response: {}",
                    refNumber, e.getMessage(), mpesaResponse != null ? mpesaResponse.getBody() : "null", e);
        }

        return customerName;
    }
}