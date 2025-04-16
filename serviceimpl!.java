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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@RequiredArgsConstructor
@Slf4j
@Service
public class TransactionServiceImpl implements TransactionService {

    private final RestTemplate restTemplate;
    Gson gson = new Gson();

    @Override
    public ConnectorResponse postTransaction(String url, String username, String password, TransactionBody transactionBody) {
        TransactionResponse transactionResponse;
        ConnectorResponse connectorResponse = new ConnectorResponse();

        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

        log.info("URL: " + url);

        try {
            // First get MPESA name if reference is available
            if (transactionBody.getTransactionref() != null && !transactionBody.getTransactionref().isEmpty()) {
                String customerName = getMpesaName(transactionBody.getTransactionref());
                log.info("Retrieved MPESA customer name: {}", customerName);
                // You can use the customer name as needed
            }

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

            log.info("response..{}..", transactionResponse);
            if (transactionResponse.getResultCode().equals("0")) {
                connectorResponse.setAccountnumber(transactionBody.getCustomerid());
                connectorResponse.setContactid(transactionBody.getCustomerid());
                connectorResponse.setResultmessage(transactionResponse.getResultDesc() + " ERP Ref Id " + transactionResponse.getErpRefId());
                connectorResponse.setResultcode(transactionResponse.getResultCode());
                connectorResponse.setResultdesc(transactionResponse.getResultDesc() + " ERP Ref Id " + transactionResponse.getErpRefId());
            } else {
                connectorResponse.setResultcode(transactionResponse.getResultCode());
                connectorResponse.setResultmessage(transactionResponse.getResultDesc());
            }
            return connectorResponse;
        } catch (Exception e) {
            throw new TransactionServiceException(e.getMessage());
        }
    }
    @Override
    public String getMpesaName(String refNumber) {
        String customerName = "";

        try {
            // Initialize Unirest (only needed once in your application)
            Unirest.setTimeouts(20000, 20000);

            HttpResponse<String> mpesaResponse = Unirest.post("http://192.168.205.126:1880/bcwsgateway/services/BCMpesaRefService?wsdl")
                    .header("Content-Type", "application/xml")
                    .header("Accept", "application/xml")
                    .body("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ser=\"http://www.imbank.com/service\">\r\n "
                            + "  <soapenv:Header/>\r\n  <soapenv:Body>\r\n    <ser:BCMpesaRefNameRequest>\r\n   "
                            + "  <Mpesa_Ref>" + refNumber + "</Mpesa_Ref>\r\n  "
                            + "   </ser:BCMpesaRefNameRequest>\r\n "
                            + " </soapenv:Body>\r\n\r\n</soapenv:Envelope>")
                    .asString();

            log.info("MPESA response body ===> " + mpesaResponse.getBody());

            // Parse the XML response
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            // Add these for security
            dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();

            // Convert response body to InputStream
            InputStream inputStream = new ByteArrayInputStream(mpesaResponse.getBody().getBytes(StandardCharsets.UTF_8));
            Document doc = dBuilder.parse(inputStream);
            doc.getDocumentElement().normalize();

            log.info("Root element: " + doc.getDocumentElement().getNodeName());

            // Get customer name from the response
            NodeList nodeList = doc.getElementsByTagName("Cust_Name");
            if (nodeList.getLength() > 0) {
                customerName = nodeList.item(0).getTextContent();
                log.info("Customer name ===> " + customerName);
            } else {
                log.warn("Customer name not found in response");
            }

        } catch (UnirestException ex) {
            log.error("Unirest error getting MPESA name for reference: " + refNumber, ex);
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            log.error("XML parsing error for MPESA reference: " + refNumber, ex);
        } catch (Exception ex) {
            log.error("Unexpected error getting MPESA name for reference: " + refNumber, ex);
        }

        return customerName;
    }
}