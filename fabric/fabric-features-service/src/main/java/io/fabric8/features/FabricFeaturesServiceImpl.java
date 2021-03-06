/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.features;

import static io.fabric8.utils.features.FeatureUtils.search;
import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.InvalidComponentException;
import io.fabric8.api.OptionsProvider;
import io.fabric8.api.Profile;
import io.fabric8.api.ProfileBuilder;
import io.fabric8.api.ProfileService;
import io.fabric8.api.Profiles;
import io.fabric8.api.Version;
import io.fabric8.api.jcip.GuardedBy;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.fabric8.utils.NamedThreadFactory;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.apache.karaf.features.internal.FeatureValidationUtil;
import org.apache.karaf.features.internal.RepositoryImpl;
import org.osgi.service.url.URLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * A FeaturesService implementation for Fabric managed containers.
 */
@ThreadSafe
@Component(name = "io.fabric8.features", label = "Fabric8 Features Service", immediate = true, metatype = false)
@Service(FeaturesService.class)
@Properties(
        @Property(name = "service.ranking", intValue = 1)
)
public final class FabricFeaturesServiceImpl extends AbstractComponent implements FeaturesService, Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeaturesService.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("fabric-features"));

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = URLStreamHandlerService.class, target = "(url.handler.protocol=mvn)")
    private final ValidatingReference<URLStreamHandlerService> urlHandler = new ValidatingReference<URLStreamHandlerService>();

    @GuardedBy("this")
    private final LoadingCache<String, Repository> repositories = CacheBuilder.newBuilder().build(new CacheLoader<String, Repository>() {
        @Override
        public Repository load(String uri) throws Exception {
            RepositoryImpl repository = new RepositoryImpl(new URI(uri));
            repository.load();
            return repository;
        }
    });

    @GuardedBy("this")
    private final Set<Repository> installedRepositories = new HashSet<Repository>();
    @GuardedBy("this")
    private final Set<Feature> installedFeatures = new HashSet<Feature>();

    @Activate
    void activate() {
        fabricService.get().trackConfiguration(this);
        activateComponent();
        executor.submit(this);
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
        fabricService.get().untrackConfiguration(this);
    }

    @Override
    public synchronized void run() {
        assertValid();
        boolean updated = false;
        final int MAX_RETRIES = 10;
        int iteration = 0;
        while(!updated && iteration < MAX_RETRIES) {
            try {
                iteration = iteration + 1;
                List<Repository> listInstalledRepositories = Arrays.asList(listInstalledRepositories());
                List<Feature> listInstalledFeatures = Arrays.asList(listInstalledFeatures());
                repositories.invalidateAll();

                installedRepositories.clear();
                installedFeatures.clear();

                installedRepositories.addAll(listInstalledRepositories);
                installedFeatures.addAll(listInstalledFeatures);
                updated = true;
                LOGGER.info("Features confguration correctly set");
            } catch (IllegalStateException | InvalidComponentException e){
                interruptibleThreadSleep(5000L);
            }
            if(!updated) {
                LOGGER.info("Connection to Zookeeper was not available. Retrying...");
            }
        }
        if(!updated){
            LOGGER.info("It's been impossible to correctly set features configuration after {} retries", MAX_RETRIES);
        }
    }

    protected void interruptibleThreadSleep(long timeout){
        try {
            Thread.sleep(5000L);
        } catch (InterruptedException e1) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operation interrupted while waiting for Zookeeper Connection to be available.", e1);
        }
    }

    @Override
    public void validateRepository(URI uri) throws Exception {
        assertValid();
        FeatureValidationUtil.validate(uri);
    }

    @Override
    public void addRepository(URI uri) throws Exception {
        unsupportedAddRepository(uri);
    }

    @Override
    public void addRepository(URI uri, boolean b) throws Exception {
        unsupportedAddRepository(uri);
    }

    private void unsupportedAddRepository(URI uri) {
        throw new UnsupportedOperationException(String.format("The container is managed by fabric, please use fabric:profile-edit --repository %s target-profile instead. See fabric:profile-edit --help for more information.", uri.toString()));
    }

    @Override
    public void removeRepository(URI uri) throws Exception {
        unsupportedRemoveRepository(uri);
    }

    @Override
    public void removeRepository(URI uri, boolean b) throws Exception {
        unsupportedRemoveRepository(uri);
    }

    private void unsupportedRemoveRepository(URI uri) {
        throw new UnsupportedOperationException(String.format("The container is managed by fabric, please use fabric:profile-edit --delete --repository %s target-profile instead. See fabric:profile-edit --help for more information.", uri.toString()));
    }

    @Override
    public void restoreRepository(URI uri) throws Exception {
    }

    /**
     * Lists all {@link Repository} entries found in any {@link Profile} of the current {@link Container} {@link Version}.
     */
    @Override
    public Repository[] listRepositories() {
        assertValid();
        Set<Repository> repos = new LinkedHashSet<Repository>();
        for (String uri : getAllProfilesOverlay().getRepositories()) {
            try {
                populateRepositories(uri, repos);
            } catch (Exception ex) {
                LOGGER.warn("Error while populating repositories from uri.", ex);
            }
        }

        return repos.toArray(new Repository[repos.size()]);
    }

    @Override
    public void installFeature(String s) throws Exception {
        unsupportedInstallFeature(s);
    }

    @Override
    public void installFeature(String s, EnumSet<Option> options) throws Exception {
        unsupportedInstallFeature(s);
    }

    @Override
    public void installFeature(String s, String s2) throws Exception {
        String featureName = s;
        if (s2 != null && s2.equals("0.0.0")) {
            featureName = s + "/" + s2;
        }
        unsupportedInstallFeature(featureName);
    }

    @Override
    public void installFeature(String s, String s2, EnumSet<Option> options) throws Exception {
        String featureName = s;
        if (s2 != null && s2.equals("0.0.0")) {
            featureName = s + "/" + s2;
        }
        unsupportedInstallFeature(featureName);
    }

    @Override
    public void installFeature(Feature feature, EnumSet<Option> options) throws Exception {
        unsupportedInstallFeature(feature.getName());
    }

    @Override
    public void installFeatures(Set<Feature> features, EnumSet<Option> options) throws Exception {
        StringBuffer sb = new StringBuffer();
        for (Feature feature : features) {
            sb.append("--feature ").append(feature.getName());
        }
        unsupportedInstallFeature(sb.toString());
    }

    private void unsupportedInstallFeature(String s) {
        throw new UnsupportedOperationException(String.format("The container is managed by fabric, please use fabric:profile-edit --feature %s target-profile instead. See fabric:profile-edit --help for more information.", s));
    }

    @Override
    public void uninstallFeature(String s) throws Exception {
        unsupportedUninstallFeature(s);
    }

    @Override
    public void uninstallFeature(String s, String s2) throws Exception {
        String featureName = s;
        if (s2 != null && s2.equals("0.0.0")) {
            featureName = s + "/" + s2;
        }
        unsupportedUninstallFeature(featureName);
    }

    @Override
    public void uninstallFeature(String s, EnumSet<Option> options) throws Exception {
        uninstallFeature(s);
    }

    @Override
    public void uninstallFeature(String s, String s2, EnumSet<Option> options) throws Exception {
        uninstallFeature(s, s2);
    }

    private void unsupportedUninstallFeature(String s) {
        throw new UnsupportedOperationException(String.format("The container is managed by fabric, please use fabric:profile-edit --delete --feature %s target-profile instead. See fabric:profile-edit --help for more information.", s));
    }

    @Override
    public Feature[] listFeatures() throws Exception {
        assertValid();
        Set<Feature> allfeatures = new HashSet<Feature>();
            Repository[] repositories = listRepositories();
            for (Repository repository : repositories) {
                try {
                    for (Feature feature : repository.getFeatures()) {
                        if (!allfeatures.contains(feature)) {
                            allfeatures.add(feature);
                        }
                    }
                } catch (Exception ex) {
                    LOGGER.debug("Could not load features from %s.", repository.getURI());
                }
            }
        return allfeatures.toArray(new Feature[allfeatures.size()]);
    }

    @Override
    public Feature[] listInstalledFeatures() {
        assertValid();
        Set<Feature> installed = new HashSet<Feature>();
            try {
                Map<String, Map<String, Feature>> allFeatures = getFeatures(installedRepositories);
                Profile overlayProfile = fabricService.get().getCurrentContainer().getOverlayProfile();
                Profile effectiveProfile = Profiles.getEffectiveProfile(fabricService.get(), overlayProfile);
                for (String featureName : effectiveProfile.getFeatures()) {
                    try {
                        Feature f;
                        if (featureName.contains("/")) {
                            String[] parts = featureName.split("/");
                            String name = parts[0];
                            String version = parts[1];
                            f = allFeatures.get(name).get(version);
                        } else {
                            TreeMap<String, Feature> versionMap = (TreeMap<String, Feature>) allFeatures.get(featureName);
                            f = versionMap.lastEntry().getValue();
                        }
                        addFeatures(f, installed);
                    } catch (Exception ex) {
                        LOGGER.debug("Error while adding {} to the features list");
                    }
                }
            } catch (IllegalStateException e){
                if ("Client is not started".equals(e.getMessage())){
                    LOGGER.warn("Zookeeper connection not available. It's not yet possible to compute features.");
                }
            } catch (Exception e) {
                LOGGER.error("Error retrieving features.", e);
            }
        return installed.toArray(new Feature[installed.size()]);
    }


    @Override
    public boolean isInstalled(Feature feature) {
        assertValid();
        return installedFeatures.contains(feature);
    }

    @Override
    public Feature getFeature(String name) throws Exception {
        assertValid();
        Feature[] features = listFeatures();
        for (Feature feature : features) {
            if (name.equals(feature.getName())) {
                return feature;
            }
        }
        return null;
    }

    @Override
    public Feature getFeature(String name, String version) throws Exception {
        assertValid();
        Feature[] features = listFeatures();
        for (Feature feature : features) {
            if (name.equals(feature.getName()) && version.equals(feature.getVersion())) {
                return feature;
            }
        }
        return null;
    }


    private Map<String, Map<String, Feature>> getFeatures(Iterable<Repository> repositories) throws Exception {
        Map<String, Map<String, Feature>> features = new HashMap<String, Map<String, Feature>>();
        for (Repository repo : repositories) {
            try {
                for (Feature f : repo.getFeatures()) {
                    if (features.get(f.getName()) == null) {
                        Map<String, Feature> versionMap = new TreeMap<String, Feature>();
                        versionMap.put(f.getVersion(), f);
                        features.put(f.getName(), versionMap);
                    } else {
                        features.get(f.getName()).put(f.getVersion(), f);
                    }
                }
            } catch (Exception ex) {
                LOGGER.debug("Could not load features from %s.", repo.getURI());
            }
        }
        return features;
    }


    /**
     * Lists all {@link Repository} enties found in the {@link Profile}s assigned to the current {@link Container}.
     */
    private Repository[] listInstalledRepositories() {
        Set<String> repositoryUris = new LinkedHashSet<String>();
        Set<Repository> repos = new LinkedHashSet<Repository>();

        Profile overlayProfile = fabricService.get().getCurrentContainer().getOverlayProfile();
        Profile effectiveProfile = Profiles.getEffectiveProfile(fabricService.get(), overlayProfile);
        if (effectiveProfile.getRepositories() != null) {
            for (String uri : effectiveProfile.getRepositories()) {
                repositoryUris.add(uri);
            }
        }

        for (String uri : repositoryUris) {
            try {
                populateRepositories(uri, repos);
            } catch (Exception ex) {
                LOGGER.warn("Error while populating repositories from uri: {} ", uri, ex);
            }
        }

        return repos.toArray(new Repository[repos.size()]);
    }

    private void populateRepositories (String uri, Set<Repository> repos) throws Exception {
        Repository repository = repositories.get(uri);
        if (repository != null && !repos.contains(repository)) {
            repos.add(repository);
            for (URI u : repository.getRepositories()) {
                populateRepositories(u.toString(), repos);
            }
        }
    }

    /**
     * Adds {@link Feature} and its dependencies to the set of {@link Feature}s.
     */
    private void addFeatures(Feature feature, Set<Feature> features) {
        if (features.contains(feature)) {
            return;
        }

        features.add(feature);
        for (Feature dependency : feature.getDependencies()) {
            addFeatures(search(dependency.getName(), dependency.getVersion(), repositories.asMap().values()), features);
        }
    }

    /**
     * Creates an aggregation of all available {@link Profile}s.
     */
    private Profile getAllProfilesOverlay() {
        Container container = fabricService.get().getCurrentContainer();
        ProfileService profileService = fabricService.get().adapt(ProfileService.class);
        Version version = container.getVersion();
        Profile versionProfile = getVersionProfile(version);
        return Profiles.getEffectiveProfile(fabricService.get(), profileService.getOverlayProfile(versionProfile));
    }

	private Profile getVersionProfile(Version version) {
		String profileId = "#version-" + version.getId();
		ProfileBuilder builder = ProfileBuilder.Factory.create(profileId).version(version.getId());
		VersionProfileOptionsProvider optionsProvider = new VersionProfileOptionsProvider(version);
		return builder.addOptions(optionsProvider).getProfile();
	}

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindUrlHandler(URLStreamHandlerService urlHandler) {
        this.urlHandler.bind(urlHandler);
    }

    void unbindUrlHandler(URLStreamHandlerService urlHandler) {
        this.urlHandler.unbind(urlHandler);
    }

    static class VersionProfileOptionsProvider implements OptionsProvider<ProfileBuilder> {

    	private final Version version;
    	
        private VersionProfileOptionsProvider(Version version) {
        	this.version = version;
        }

        @Override
		public ProfileBuilder addOptions(ProfileBuilder builder) {
			return builder.addParents(version.getProfileIds());
		}
    }
}
