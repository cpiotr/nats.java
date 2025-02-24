// Copyright 2023 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import io.nats.client.Options;
import io.nats.client.ServerPool;
import io.nats.client.support.NatsConstants;
import io.nats.client.support.NatsUri;

import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class NatsServerPool implements ServerPool {

    private final Object listLock;
    private List<ServerPoolEntry> entryList;
    private Options options;
    private int maxConnectAttempts;
    private NatsUri lastConnected;
    private boolean hasSecureServer;
    private String defaultScheme;

    public NatsServerPool() {
        listLock = new Object();
    }

    /**
     * {@inheritDoc}
     */
    public void initialize(Options opts) {
        // 1. Hold on to options as we need them for settings
        options = opts;

        // 2. maxConnectAttempts accounts for the first connect attempt and also reconnect attempts
        maxConnectAttempts = options.getMaxReconnect() < 0 ? Integer.MAX_VALUE : options.getMaxReconnect() + 1;

        // 3. Add all the bootstrap to the server list and prepare list for next
        //    FYI bootstrap will always have at least the default url
        synchronized (listLock) {
            entryList = new ArrayList<>();
            for (NatsUri nuri : options.getNatsServerUris()) {
                // 1. If item is not found in the list being built, add to the list
                boolean notAlreadyInList = true;
                for (ServerPoolEntry entry : entryList) {
                    if (nuri.equivalent(entry.nuri)) {
                        notAlreadyInList = false;
                        break;
                    }
                }
                if (notAlreadyInList) {
                    if (defaultScheme == null && !nuri.getScheme().equals(NatsConstants.NATS_PROTOCOL)) {
                        defaultScheme = nuri.getScheme();
                    }
                    entryList.add(new ServerPoolEntry(nuri, false));
                }
            }

            // 6. prepare list for next
            afterListChanged();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acceptDiscoveredUrls(List<String> discoveredServers) {
        // 1. If ignored discovered servers, don't do anything b/c never want
        //    anything but the explicit, which is already loaded.
        // 2. return false == no new servers discovered
        if (options.isIgnoreDiscoveredServers()) {
            return false;
        }

        synchronized (listLock) {
            // 2. Build a list for discovered
            //    - since we'll need the NatsUris later
            //    - and to have a list to use to prune removed gossiped servers
            List<NatsUri> discovered = new ArrayList<>();
            for (String d : discoveredServers) {
                try {
                    discovered.add(new NatsUri(d, defaultScheme));
                } catch (URISyntaxException ignore) {
                    // should never actually happen
                }
            }

            // 3. Start a new server list, loading in current order from the current list, and keeping
            //    - the last connected
            //    - all non-gossiped
            //    - any found in the new discovered list
            //      - for any new discovered, we also remove them from
            //        that list so step there are no dupes for step #4
            //      - This also maintains the Srv state of an already known discovered
            List<ServerPoolEntry> newEntryList = new ArrayList<>();
            for (ServerPoolEntry entry : entryList) {
                int ix = findEquivalent(discovered, entry.nuri);
                if (ix != -1 || entry.nuri.equals(lastConnected) || !entry.isGossiped) {
                    newEntryList.add(entry);
                    if (ix != -1) {
                        discovered.remove(ix);
                    }
                }
            }

            // 4. Add all left over from the new discovered list
            boolean discoveryContainedUnknowns = false;
            if (discovered.size() > 0) {
                discoveryContainedUnknowns = true;
                for (NatsUri d : discovered) {
                    newEntryList.add(new ServerPoolEntry(d, true));
                }
            }

            // 5. replace the list with the new one
            entryList = newEntryList;

            // 6. prepare list for next
            afterListChanged();

            // 7.
            return discoveryContainedUnknowns;
        }
    }

    private void afterListChanged() {
        // 1. randomize if needed and allowed
        if (entryList.size() > 1 && !options.isNoRandomize()) {
            Collections.shuffle(entryList, ThreadLocalRandom.current());
        }

        // 2. calculate hasSecureServer and find the index of lastConnected
        hasSecureServer = false;
        int lastConnectedIx = -1;
        for (int ix = 0; ix < entryList.size(); ix++) {
            NatsUri nuri = entryList.get(ix).nuri;
            hasSecureServer |= nuri.isSecure();
            if (nuri.equals(lastConnected)) {
                lastConnectedIx = ix;
            }
        }

        // C. put the last connected server at the end of the list
        if (lastConnectedIx != -1) {
            entryList.add(entryList.remove(lastConnectedIx));
        }
    }

    @Override
    public NatsUri peekNextServer() {
        synchronized (listLock) {
            return entryList.size() > 0 ? entryList.get(0).nuri : null;
        }
    }

    @Override
    public NatsUri nextServer() {
        // 0. The list is already managed for qualified by connectFailed
        // 1. Get the first item in the list, update it's time, add back to the end of list
        synchronized (listLock) {
            if (entryList.size() > 0) {
                ServerPoolEntry entry = entryList.remove(0);
                entry.lastAttempt = System.currentTimeMillis();
                entryList.add(entry);
                return entry.nuri;
            }
            return null;
        }
    }

    @Override
    public List<String> resolveHostToIps(String host) {
        // 1. if options.isNoResolveHostnames(), return empty list
        if (options.isNoResolveHostnames()) {
            return null;
        }

        // 2. else, try to resolve the hostname, adding results to list
        List<String> results = new ArrayList<>();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress a : addresses) {
                results.add(a.getHostAddress());
            }
        }
        catch (UnknownHostException ignore) {
            // A user might have supplied a bad host, but the server shouldn't.
            // Either way, nothing much we can do.
        }

        // 3. no results, return null.
        if (results.size() == 0) {
            return null;
        }

        // 4. if results has more than 1 and allowed to randomize, shuffle the list
        if (results.size() > 1 && !options.isNoRandomize()) {
            Collections.shuffle(results, ThreadLocalRandom.current());
        }
        return results;
    }

    @Override
    public void connectSucceeded(NatsUri nuri) {
        // 1. Work from the end because nextServer moved the one being tried to the end
        // 2. If we find the server in the list...
        //    2.1. remember it and
        //    2.2. reset failed attempts
        synchronized (listLock) {
            for (int x = entryList.size() - 1; x >= 0 ; x--) {
                ServerPoolEntry entry = entryList.get(x);
                if (entry.nuri.equals(nuri)) {
                    lastConnected = nuri;
                    entry.failedAttempts = 0;
                    return;
                }
            }
        }
    }

    @Override
    public void connectFailed(NatsUri nuri) {
        // 1. Work from the end because nextServer moved the one being tried to the end
        // 2. If we find the server in the list...
        //    2.1. increment failed attempts
        //    2.2. if failed attempts reaches max, remove it from the list
        synchronized (listLock) {
            for (int x = entryList.size() - 1; x >= 0 ; x--) {
                ServerPoolEntry entry = entryList.get(x);
                if (entry.nuri.equals(nuri)) {
                    if (++entry.failedAttempts >= maxConnectAttempts) {
                        entryList.remove(x);
                    }
                    return;
                }
            }
        }
    }

    @Override
    public List<String> getServerList() {
        synchronized (listLock) {
            List<String> list = new ArrayList<>();
            for (ServerPoolEntry entry : entryList) {
                list.add(entry.nuri.toString());
            }
            return list;
        }
    }

    @Override
    public boolean hasSecureServer() {
        return hasSecureServer;
    }

    private int findEquivalent(List<NatsUri> list, NatsUri toFind) {
        for (int i = 0; i < list.size(); i++) {
            NatsUri nuri = list.get(i);
            if (nuri.equivalent(toFind)) {
                return i;
            }
        }
        return -1;
    }
}
