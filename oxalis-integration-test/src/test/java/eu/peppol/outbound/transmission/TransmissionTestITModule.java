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

package eu.peppol.outbound.transmission;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Named;
import eu.peppol.outbound.IntegrationTestConstant;
import no.difi.oxalis.outbound.transmission.TransmissionModule;
import no.difi.oxalis.persistence.guice.DataSourceModule;
import no.difi.oxalis.statistics.guice.RawStatisticsRepositoryModule;
import no.difi.oxalis.api.lookup.LookupService;
import no.difi.oxalis.commons.config.ConfigModule;
import no.difi.oxalis.commons.filesystem.FileSystemModule;
import org.mockito.Mockito;

import java.io.InputStream;

import static org.testng.Assert.assertNotNull;

/**
 * @author steinar
 * @author thore
 */
public class TransmissionTestITModule extends AbstractModule {

    public static final String OUR_LOCAL_OXALIS_URL = IntegrationTestConstant.OXALIS_AS2_URL;

    @Override
    protected void configure() {
        binder().install(new ConfigModule());
        binder().install(new FileSystemModule());

        binder().install(new DataSourceModule());
        binder().install(new RawStatisticsRepositoryModule());
        binder().install(new TransmissionModule());
    }

    @Provides
    @Named("sample-ehf-invoice-no-sbdh")
    public InputStream getSampleEhfInvoice() {
        InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream("EHF-Invoice-2.0.8-no-sbdh.xml");
        assertNotNull(resourceAsStream, "Unable to load " + "EHF-Invoice-2.0.8-no-sbdh.xml" + " from class path");
        return resourceAsStream;
    }

    @Provides
    @Named("sample-xml-with-sbdh")
    public InputStream getSampleXmlInputStream() {
        InputStream resourceAsStream = getClass().getClassLoader()
                .getResourceAsStream("peppol-bis-invoice-sbdh.xml");
        assertNotNull(resourceAsStream, "Unable to load " + "peppol-bis-invoice-sbdh.xml" + " from class path");
        return resourceAsStream;
    }

    @Provides
    @Named("invoice-to-itsligo")
    public InputStream sampleInvoiceWithSbdhForItSligo() {
        InputStream resourceAsStream = getClass().getClassLoader()
                .getResourceAsStream("peppol-bis-invoice-sbdh-itsligo.xml");
        assertNotNull(resourceAsStream, "Unable to load " + "peppol-bis-invoice-sbdh-itsligo.xml" + " from class path");
        return resourceAsStream;
    }

    @Provides
    public LookupService getFakeLookupService() {
        return Mockito.mock(LookupService.class);
    }
}
