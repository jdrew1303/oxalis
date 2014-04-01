package eu.peppol.outbound.transmission;

import com.google.inject.Inject;
import eu.peppol.as2.*;
import eu.peppol.identifier.ParticipantId;
import eu.peppol.identifier.TransmissionId;
import eu.peppol.security.CommonName;
import eu.peppol.security.KeystoreManager;
import eu.peppol.smp.SmpLookupManager;
import eu.peppol.identifier.PeppolDocumentTypeId;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Thread safe implementation of a {@link MessageSender}, which sends messages using the AS2 protocol.
 *
 * @author steinar
 * @author thore
 */
class As2MessageSender implements MessageSender {

    public static final Logger log = LoggerFactory.getLogger(As2MessageSender.class);

    private final SmpLookupManager smpLookupManager;

    @Inject
    public As2MessageSender(final SmpLookupManager smpLookupManager) {
        this.smpLookupManager = smpLookupManager;
    }

    @Override
    public TransmissionResponse send(TransmissionRequest transmissionRequest) {
        if (transmissionRequest.getEndpointAddress().getCommonName() == null) {
            throw new IllegalStateException("Must supply the X.509 common name (AS2 System Identifier) for AS2 protocol");
        }

        ByteArrayInputStream inputStream = new ByteArrayInputStream(transmissionRequest.getPayload());

        X509Certificate ourCertificate = KeystoreManager.INSTANCE.getOurCertificate();
        PeppolAs2SystemIdentifier as2SystemIdentifierOfSender = getAs2SystemIdentifierForSender(ourCertificate);

        TransmissionId transmissionId = send(inputStream,
                transmissionRequest.getPeppolStandardBusinessHeader().getRecipientId(),
                transmissionRequest.getPeppolStandardBusinessHeader().getSenderId(),
                transmissionRequest.getPeppolStandardBusinessHeader().getDocumentTypeIdentifier(),
                transmissionRequest.getEndpointAddress(),
                as2SystemIdentifierOfSender);

        return new As2TransmissionResponse(transmissionId, transmissionRequest.getPeppolStandardBusinessHeader());
    }


    TransmissionId send(InputStream inputStream, ParticipantId recipient, ParticipantId sender, PeppolDocumentTypeId peppolDocumentTypeId, SmpLookupManager.PeppolEndpointData peppolEndpointData, PeppolAs2SystemIdentifier as2SystemIdentifierOfSender) {


        if (peppolEndpointData.getCommonName() == null) {
            throw new IllegalArgumentException("No common name in EndPoint object. " + peppolEndpointData);
        }
        X509Certificate ourCertificate = KeystoreManager.INSTANCE.getOurCertificate();

        SMimeMessageFactory SMimeMessageFactory = new SMimeMessageFactory(KeystoreManager.INSTANCE.getOurPrivateKey(), ourCertificate);
        MimeMessage signedMimeMessage = null;
        try {
            signedMimeMessage = SMimeMessageFactory.createSignedMimeMessage(inputStream, new MimeType("application/xml"));
        } catch (MimeTypeParseException e) {
            throw new IllegalStateException("Problems with MIME types: " + e.getMessage(), e);
        }

        CloseableHttpClient httpClient = createCloseableHttpClient();

        String endpointAddress = peppolEndpointData.getUrl().toExternalForm();
        HttpPost httpPost = new HttpPost(endpointAddress);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            signedMimeMessage.writeTo(byteArrayOutputStream);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to stream S/MIME message into byte array output stream");
        }

        httpPost.addHeader(As2Header.AS2_FROM.getHttpHeaderName(), as2SystemIdentifierOfSender.toString());
        try {
            httpPost.setHeader(As2Header.AS2_TO.getHttpHeaderName(), PeppolAs2SystemIdentifier.valueOf(peppolEndpointData.getCommonName()).toString());
        } catch (InvalidAs2SystemIdentifierException e) {
            throw new IllegalArgumentException("Unable to create valid AS2 System Identifier for receiving end point: " + peppolEndpointData);
        }

        httpPost.addHeader(As2Header.DISPOSITION_NOTIFICATION_TO.getHttpHeaderName(), "aksesspunkt@difi.no");
        httpPost.addHeader(As2Header.DISPOSITION_NOTIFICATION_OPTIONS.getHttpHeaderName(), As2DispositionNotificationOptions.getDefault().toString());
        httpPost.addHeader(As2Header.AS2_VERSION.getHttpHeaderName(), As2Header.VERSION);
        httpPost.addHeader(As2Header.SUBJECT.getHttpHeaderName(), "AS2 message from OXALIS");

        TransmissionId transmissionId = new TransmissionId();
        httpPost.addHeader(As2Header.MESSAGE_ID.getHttpHeaderName(), transmissionId.toString());
        httpPost.addHeader(As2Header.DATE.getHttpHeaderName(), As2DateUtil.format(new Date()));

        /*
        Non-normative AS2 Headers example from the PEPPOL Transport Infrastructure AS2 Profile
        ======================================================================================
        content-disposition = attachment; filename="smime.p7m"
        as2-from = APP_1000000002
        connection = close, TE
        ediint-features = multiple-attachments, CEM
        date = Fri, 29 Nov 2013 15:12:00 CET
        as2-to = APP_1000000003
        disposition-notification-to = http://domain.com/cipa-as2-access-point- wrapper/AS2Receiver
        message-id = <mendelson_opensource_AS2-1385734320013-0@APP_1000000002_mend> subject = AS2 message
        from = as2@company.com
        as2-version = 1.2
        disposition-notification-options = signed-receipt-protocol=optional, pkcs7-signature; signed-receipt-micalg=optional, sha1, md5
        content-type = multipart/signed; protocol="application/pkcs7-signature"; micalg=sha1; boundary="----=_Part_1_1908557897.1385734320094"
        host = as2server.DestAP.com
        mime-version = 1.0
        recipient-address = http://domain.com/cipa-as2-access-point- wrapper/AS2Receiver
        */

        /*
RECEIVED AT OXALIS END :

Content-Type: multipart/signed; protocol="application/pkcs7-signature"; micalg=sha-1;

Content-Type: text/plain
Content-Transfer-Encoding: 7bit

The following headers were received:
date: Mon, 31 Mar 2014 16:01:10 +0200
message-id: ef4c088f-98ce-47af-8a7a-4aea03dad59f
subject: AS2 message from OXALIS
content-type: application/xml; charset=ISO-8859-1
host: ap-test.unit4.com
x-forwarded-for: 195.1.61.4
connection: close
as2-from: APP_1000000006
as2-to: APP_1000000006
disposition-notification-to: aksesspunkt@difi.no
disposition-notification-options: signed-receipt-protocol=required,pkcs7-signature; signed-receipt-micalg=required,sha1
as2-version: 1.0
user-agent: Apache-HttpClient/4.3.1 (java 1.5)
accept-encoding: gzip,deflate
content-length: 144519
         */

        // Inserts the S/MIME message to be posted
        httpPost.setEntity(new ByteArrayEntity(byteArrayOutputStream.toByteArray(), ContentType.APPLICATION_XML));

        CloseableHttpResponse postResponse = null;      // EXECUTE !!!!
        try {
            log.info("Sending message to " + endpointAddress);
            postResponse = httpClient.execute(httpPost);
        } catch (HttpHostConnectException e) {
            throw new IllegalStateException("The Oxalis server does not seem to be running at " + endpointAddress);
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected error during execution of http POST to " + endpointAddress + ": " + e.getMessage(), e);
        }

        if (postResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            log.error("AS2 HTTP POST expected HTTP OK, but got : " + postResponse.getStatusLine().getStatusCode());
            return handleFailedRequest(postResponse);
        }

        return handleTheHttpResponse(transmissionId, postResponse);

    }

    TransmissionId handleTheHttpResponse(TransmissionId transmissionId, CloseableHttpResponse postResponse) {
        try {
            HttpEntity entity = postResponse.getEntity();   // Any textual results?
            if (entity == null) {
                throw new IllegalStateException("No contents in HTTP response with rc=" + postResponse.getStatusLine().getStatusCode());
            }

            String contents = EntityUtils.toString(entity);

            if (log.isDebugEnabled()) {
                log.debug("Received:");
                Header[] allHeaders = postResponse.getAllHeaders();
                for (Header header : allHeaders) {
                    log.debug("" + header.getName() + ": " + header.getValue());
                }
                log.debug("Contents:\n" + contents);
                log.debug("---------------------------");
            }

            Header contentTypeHeader = postResponse.getFirstHeader("Content-Type");
            if (contentTypeHeader == null) {
                throw new IllegalStateException("No Content-Type header in response, probably a server error");
            }
            String contentType = contentTypeHeader.getValue();

            MimeMessage mimeMessage = null;
            try {
                mimeMessage = MimeMessageHelper.parseMultipart(contents, new MimeType(contentType));
            } catch (MimeTypeParseException e) {
                throw new IllegalStateException("Invalid Content-Type header");
            }

            MdnMimeMessageInspector mdnMimeMessageInspector = new MdnMimeMessageInspector(mimeMessage);

            String msg = mdnMimeMessageInspector.getPlainTextPartAsText();

            if (mdnMimeMessageInspector.isOk()) {
                return transmissionId;
            } else {
                log.error("AS2 transmission failed with some error message, msg:" + msg);
                log.error(contents);
                throw new IllegalStateException("Transmission failed : " + msg);
            }

        } catch (IOException e) {
            throw new IllegalStateException("Unable to obtain the contents of the response: " + e.getMessage(), e);
        } finally {
            try {
                postResponse.close();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to close http connection: " + e.getMessage(), e);
            }
        }
    }

    TransmissionId handleFailedRequest(CloseableHttpResponse postResponse) {
        HttpEntity entity = postResponse.getEntity();   // Any results?
        try {
            if (entity == null) {
                // No content returned
                throw new IllegalStateException("Request failed with rc=" + postResponse.getStatusLine().getStatusCode() + ", no content returned in HTTP response");
            } else {
                String contents =  EntityUtils.toString(entity);
                throw new IllegalStateException("Request failed with rc=" + postResponse.getStatusLine().getStatusCode() + ", contents received ("+ contents.trim().length() + " characters):" + contents);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Request failed with rc=" + postResponse.getStatusLine().getStatusCode()
                    + ", ERROR while retrieving the contents of the response:" + e.getMessage(),e);
        }
    }

    private PeppolAs2SystemIdentifier getAs2SystemIdentifierForSender(X509Certificate ourCertificate) {
        PeppolAs2SystemIdentifier peppolAs2SystemIdentifier = null;
        try {
            peppolAs2SystemIdentifier = PeppolAs2SystemIdentifier.valueOf(CommonName.valueOf(ourCertificate.getSubjectX500Principal()));
        } catch (InvalidAs2SystemIdentifierException e) {
            throw new IllegalStateException("AS2 System Identifier could not be obtained from " + ourCertificate.getSubjectX500Principal(), e);
        }
        return peppolAs2SystemIdentifier;
    }

    private CloseableHttpClient createCloseableHttpClient() {
        SSLContext sslcontext = SSLContexts.createSystemDefault();
        // Use custom hostname verifier to customize SSL hostname verification.
        X509HostnameVerifier hostnameVerifier = new AllowAllHostnameVerifier();

        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(sslcontext, SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        CloseableHttpClient httpclient = HttpClients.custom()
                .setSSLSocketFactory(sslsf)
                .build();

        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.INSTANCE)
                .register("https", new SSLConnectionSocketFactory(sslcontext, hostnameVerifier))
                .build();
        return httpclient;
    }

}