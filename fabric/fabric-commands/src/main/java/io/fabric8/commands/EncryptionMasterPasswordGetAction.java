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
package io.fabric8.commands;

import io.fabric8.utils.PasswordEncoder;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.gogo.commands.Command;
import org.apache.karaf.shell.console.AbstractAction;

import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getStringData;

@Command(name = EncryptionMasterPasswordGet.FUNCTION_VALUE, scope = EncryptionMasterPasswordGet.SCOPE_VALUE, description = EncryptionMasterPasswordGet.DESCRIPTION)
public class EncryptionMasterPasswordGetAction extends AbstractAction {

    private final CuratorFramework curator;

    EncryptionMasterPasswordGetAction(CuratorFramework curator) {
        this.curator = curator;
    }

    public CuratorFramework getCurator() {
        return curator;
    }

    @Override
    protected Object doExecute() throws Exception {
        if (exists(getCurator(), ZkPath.AUTHENTICATION_CRYPT_PASSWORD.getPath()) != null) {
            System.out.println(PasswordEncoder.decode(getStringData(getCurator(), ZkPath.AUTHENTICATION_CRYPT_PASSWORD.getPath())));
        }
        return null;
    }
}
