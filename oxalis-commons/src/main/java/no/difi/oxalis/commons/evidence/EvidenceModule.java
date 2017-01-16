package no.difi.oxalis.commons.evidence;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import no.difi.oxalis.api.evidence.EvidenceFactory;
import no.difi.vefa.peppol.mode.Mode;

import javax.inject.Singleton;

public class EvidenceModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(Key.get(EvidenceFactory.class, Names.named("rem"))).to(RemEvidenceFactory.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    EvidenceFactory getEvidenceFactory(Injector injector, Mode mode) {
        return injector.getInstance(Key.get(EvidenceFactory.class, Names.named(mode.getString("evidence"))));
    }
}