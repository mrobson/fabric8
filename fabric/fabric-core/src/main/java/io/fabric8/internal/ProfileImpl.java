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
package io.fabric8.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;

import io.fabric8.api.Constants;
import io.fabric8.api.FabricException;
import io.fabric8.api.Profile;
import io.fabric8.common.util.ChecksumUtils;
import io.fabric8.utils.DataStoreUtils;

/**
 * This immutable profile implementation.
 */
final class ProfileImpl implements Profile {

    private final String versionId;
    private final String profileId;
    private final Map<String, String> attributes = new HashMap<>();
    private final List<String> parents = new ArrayList<>();
    private final Map<String, byte[]> fileConfigurations = new HashMap<>();
    private final Map<String, Map<String, String>> configurations = new HashMap<>();
    private final boolean isOverlay;
    private final String lastModified;

    // Only the {@link ProfileBuilder} should construct this
    ProfileImpl(String versionId, String profileId, List<String> parents, Map<String, byte[]> fileConfigs, String lastModified, boolean isOverlay) {
        this.profileId = profileId;
        this.versionId = versionId;
        this.lastModified = lastModified;
        this.isOverlay = isOverlay;

        // Parents
        this.parents.addAll(parents);
        
        // File configurations and derived configurations
        for (Entry<String, byte[]> entry : fileConfigs.entrySet()) {
            String fileKey = entry.getKey();
            byte[] bytes = entry.getValue();
            fileConfigurations.put(fileKey, bytes);
            if (fileKey.endsWith(Profile.PROPERTIES_SUFFIX)) {
                String pid = fileKey.substring(0, fileKey.indexOf(Profile.PROPERTIES_SUFFIX));
                configurations.put(pid, Collections.unmodifiableMap(DataStoreUtils.toMap(bytes)));
            }
        }
        
        // Attributes are agent configuration with prefix 'attribute.'  
        Map<String, String> agentConfig = configurations.get(Constants.AGENT_PID);
        if (agentConfig != null) {
            int prefixLength = Profile.ATTRIBUTE_PREFIX.length();
            for (Entry<String, String> entry : agentConfig.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(Profile.ATTRIBUTE_PREFIX)) {
                    attributes.put(key.substring(prefixLength), entry.getValue());
                }
            }
        }
    }

    public String getId() {
        return profileId;
    }

    public String getVersion() {
        return versionId;
    }

    @Override
    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public int compareTo(Profile profile) {
        return profileId.compareTo(profile.getId());
    }

    @Override
    public List<String> getLibraries() {
        return getContainerConfigList(this, ConfigListType.LIBRARIES);
    }

    @Override
    public List<String> getEndorsedLibraries() {
        return getContainerConfigList(this, ConfigListType.ENDORSED);
    }

    @Override
    public List<String> getExtensionLibraries() {
        return getContainerConfigList(this, ConfigListType.EXTENSION);
    }

    public List<String> getBundles() {
        return getContainerConfigList(this, ConfigListType.BUNDLES);
    }

    public List<String> getFabs() {
        return getContainerConfigList(this, ConfigListType.FABS);
    }

    public List<String> getFeatures() {
        return getContainerConfigList(this, ConfigListType.FEATURES);
    }

    public List<String> getRepositories() {
        return getContainerConfigList(this, ConfigListType.REPOSITORIES);
    }

    @Override
    public List<String> getOverrides() {
        return getContainerConfigList(this, ConfigListType.OVERRIDES);
    }

    @Override
    public List<String> getOptionals() {
        return getContainerConfigList(this, ConfigListType.OPTIONALS);
    }

    @Override
    public List<String> getParentIds() {
        return Collections.unmodifiableList(parents);
    }

    @Override
    public boolean isAbstract() {
        return Boolean.parseBoolean(getAttributes().get(ABSTRACT));
    }

    @Override
    public boolean isLocked() {
        return Boolean.parseBoolean(getAttributes().get(LOCKED));
    }

    @Override
    public boolean isHidden() {
        return Boolean.parseBoolean(getAttributes().get(HIDDEN));
    }

    public boolean isOverlay() {
        return isOverlay;
    }

    @Override
    public Map<String, byte[]> getFileConfigurations() {
        return Collections.unmodifiableMap(fileConfigurations);
    }

    @Override
    public Set<String> getConfigurationFileNames() {
        return Collections.unmodifiableSet(fileConfigurations.keySet());
    }

    @Override
    public byte[] getFileConfiguration(String fileName) {
        return fileConfigurations.get(fileName);
    }

    public Map<String, Map<String, String>> getConfigurations() {
        return Collections.unmodifiableMap(configurations);
    }

    @Override
    public Map<String, String> getConfiguration(String pid) {
        Map<String, String> config = configurations.get(pid);
        config = config != null ? config : Collections.<String, String> emptyMap();
        return Collections.unmodifiableMap(config);
    }

    @Override
    public String getProfileHash() {
        StringBuilder answer = new StringBuilder();
        if (lastModified != null) {
            answer.append(lastModified);
        }

        // if the profile has embedded blueprint/spring-dm bundles within the profile,
        // then we need to use a CRC of their file content in the hash calculation
        for (String bundle : getBundles()) {
            if (bundle.startsWith("blueprint:profile:") || bundle.startsWith("spring:profile:")) {
                String name = bundle.startsWith("blueprint:profile:") ? bundle.substring(18) : bundle.substring(15);
                byte[] data = fileConfigurations.get(name);
                if (data != null) {
                    InputStream is = new ByteArrayInputStream(data);
                    try {
                        long crc = ChecksumUtils.checksum(is);
                        answer.append(crc);
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }
        return answer.toString();
    }

    static List<String> getContainerConfigList(Profile p, ConfigListType type) {
        try {
            Map<String, String> containerProps = p.getConfiguration(Constants.AGENT_PID);
            List<String> rc = new ArrayList<String>();
            String prefix = type + ".";
            for (Map.Entry<String, String> e : containerProps.entrySet()) {
                if ((e.getKey()).startsWith(prefix)) {
                    rc.add(e.getValue());
                }
            }
            return rc;

        } catch (Exception e) {
            throw FabricException.launderThrowable(e);
        }
    }

    @Override
    public String getIconURL() {
        String result = null;
        Set<String> fileNames = getConfigurationFileNames();
        for (String fileName : fileNames) {
            if (fileName.startsWith("icon.")) {
                String id = getId();
                String version = getVersion();
                result = version + "/fabric/profiles/" + id + ".profile/" + fileName;
                break;
            }
        }
        return result;
    }

    @Override
    public String getIconRelativePath() {
        Set<String> fileNames = getConfigurationFileNames();
        for (String fileName : fileNames) {
            if (fileName.startsWith("icon.")) {
                return fileName;
            }
        }
        return null;
    }

    @Override
    public String getSummaryMarkdown() {
        byte[] data = getFileConfiguration("Summary.md");
        if (data != null) {
            return new String(data);
        }

        // lets return the first non heading line of the ReadMe.md as a default value
        data = getFileConfiguration("ReadMe.md");
        if (data != null) {
            String readMe = new String(data).trim();
            StringTokenizer iter = new StringTokenizer(readMe, "\n");
            boolean first = true;
            while (iter.hasMoreTokens()) {
                String text = iter.nextToken();
                if (text != null) {
                    text = text.trim();
                    // skip first heading or ==
                    if (skipSummaryLine(text) && first) {
                        first = false;
                        continue;
                    }
                    while (skipSummaryLine(text)) {
                        text = text.substring(1);
                    }
                    text = text.trim();
                    if (text.length() > 0) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private static boolean skipSummaryLine(String line) {
        return line.startsWith("=") || line.startsWith("#");
    }
    
    @Override
    public List<String> getTags() {
        List<String> answer = getContainerConfigList(this, ConfigListType.TAGS);
        if (answer == null || answer.size() == 0) {
            // lets create the default list of tags
            answer = new ArrayList<>();
            String id = getId();
            String[] paths = id.split("-");
            if (paths != null) {
                Set<String> tags = new HashSet<String>();
                for (int i = 0, last = paths.length - 1; i < last; i++) {
                    tags.add(paths[i]);
                }
                answer.addAll(tags);
            }
        }
        return answer;
    }
    
    @Override
    public int hashCode() {
        int result = profileId.hashCode();
        result = 31 * result + versionId.hashCode();
        result = 31 * result + parents.hashCode();
        result = 31 * result + configurations.hashCode();
        result = 31 * result + fileConfigurations.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProfileImpl)) return false;
        ProfileImpl other = (ProfileImpl) obj;

        // Equality based on identity
        if (!profileId.equals(other.profileId) || !versionId.equals(other.versionId))
            return false;

        // Equality based on profile content
        // [TODO] Remove content based profile equality when identity is based
        // on unique revision

        if (!parents.equals(other.parents))
            return false;

        if (!configurations.equals(other.configurations))
            return false;

        if (!fileConfigurations.equals(other.fileConfigurations))
            return false;

        return true;
    }

    @Override
    public String toString() {
        return "Profile[ver=" + versionId + ",id=" + profileId + ",atts=" + getAttributes() + "]";
    }

    enum ConfigListType {
        BUNDLES("bundle"), 
        ENDORSED("endorsed"), 
        EXTENSION("extension"), 
        FABS("fab"), 
        FEATURES("feature"), 
        LIBRARIES("lib"), 
        OPTIONALS("optional"),
        OVERRIDES("override"), 
        REPOSITORIES("repository"), 
        TAGS("tags"); 

        private String value;

        private ConfigListType(String value) {
            this.value = value;
        }

        public String toString() {
            return value;
        }
    }
}
