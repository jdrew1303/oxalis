/*
 * Copyright 2010-2017 Norwegian Agency for Public Management and eGovernment (Difi)
 *
 * Licensed under the EUPL, Version 1.1 or – as soon they
 * will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 *
 * You may not use this work except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/community/eupl/og_page/eupl
 *
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the Licence is
 * distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the Licence for the specific language governing
 * permissions and limitations under the Licence.
 */

package no.difi.oxalis.outbound.transmission;

import brave.Span;
import brave.Tracer;
import no.difi.oxalis.api.lang.OxalisTransmissionException;
import no.difi.oxalis.api.outbound.TransmissionMessage;
import no.difi.oxalis.commons.io.PeekingInputStream;
import no.difi.oxalis.commons.tracing.Traceable;
import no.difi.oxalis.sniffer.document.NoSbdhParser;
import no.difi.vefa.peppol.common.model.Header;
import no.difi.vefa.peppol.sbdh.SbdReader;
import no.difi.vefa.peppol.sbdh.SbdWriter;
import no.difi.vefa.peppol.sbdh.lang.SbdhException;
import no.difi.vefa.peppol.sbdh.util.XMLStreamUtils;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class TransmissionRequestFactory extends Traceable {

    private final NoSbdhParser noSbdhParser;

    @Inject
    public TransmissionRequestFactory(NoSbdhParser noSbdhParser, Tracer tracer) {
        super(tracer);
        this.noSbdhParser = noSbdhParser;
    }

    public TransmissionMessage newInstance(InputStream inputStream) throws IOException, OxalisTransmissionException {
        Span root = tracer.newTrace().name(getClass().getSimpleName()).start();
        try {
            return perform(inputStream, root);
        } finally {
            root.finish();
        }
    }

    public TransmissionMessage newInstance(InputStream inputStream, Span root)
            throws IOException, OxalisTransmissionException {
        Span span = tracer.newChild(root.context()).name(getClass().getSimpleName()).start();
        try {
            return perform(inputStream, span);
        } finally {
            span.finish();
        }
    }

    private TransmissionMessage perform(InputStream inputStream, Span root)
            throws IOException, OxalisTransmissionException {
        PeekingInputStream peekingInputStream = new PeekingInputStream(inputStream);

        // Read header from content to send.
        Header header;
        try {
            // Read header from SBDH.
            Span span = tracer.newChild(root.context()).name("Reading SBDH").start();
            try (SbdReader sbdReader = SbdReader.newInstance(peekingInputStream)) {
                header = sbdReader.getHeader();
                span.tag("identifier", header.getIdentifier().getValue());
            } catch (SbdhException e) {
                span.tag("exception", e.getMessage());
                throw e;
            } finally {
                span.finish();
            }
        } catch (SbdhException e) {
            // Reading complete document to memory. Sorry!
            /*
            Span span = tracer.newChild(root.context()).name("Read content to memory").start();
            byte[] payload;
            payload = ByteStreams.toByteArray(peekingInputStream.newInputStream());
            span.tag("size", String.valueOf(payload.length));
            span.finish();
            */
            byte[] payload = peekingInputStream.getContent();

            // Detect header from content.
            Span span = tracer.newChild(root.context()).name("Detect SBDH from content").start();
            try {
                header = noSbdhParser.parse(new ByteArrayInputStream(payload)).toVefa();
                span.tag("identifier", header.getIdentifier().getValue());
            } catch (IllegalStateException ex) {
                span.tag("exception", ex.getMessage());
                throw new OxalisTransmissionException(ex.getMessage(), ex);
            } finally {
                span.finish();
            }

            // Wrap content in SBDH.
            span = tracer.newChild(root.context()).name("Wrap content in SBDH").start();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (SbdWriter sbdWriter = SbdWriter.newInstance(outputStream, header)) {
                XMLStreamUtils.copy(new ByteArrayInputStream(payload), sbdWriter.xmlWriter());
            } catch (SbdhException | XMLStreamException ex) {
                span.tag("exception", ex.getMessage());
                throw new OxalisTransmissionException("Unable to wrap content in SBDH.", ex);
            } finally {
                span.finish();
            }

            // Preparing wrapped content for sending.
            peekingInputStream = new PeekingInputStream(new ByteArrayInputStream(outputStream.toByteArray()));
        }

        // Create transmission request.
        return new DefaultTransmissionMessage(header, peekingInputStream.newInputStream());
    }
}
