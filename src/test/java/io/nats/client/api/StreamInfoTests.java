// Copyright 2020 The NATS Authors
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

package io.nats.client.api;

import io.nats.client.support.DateTimeUtils;
import io.nats.client.support.JsonParser;
import io.nats.client.support.JsonValue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nats.client.support.ApiConstants.DELETED_DETAILS;
import static io.nats.client.support.ApiConstants.SUBJECTS_FILTER;
import static io.nats.client.utils.ResourceUtils.dataAsString;
import static io.nats.client.utils.TestBase.getDataMessage;
import static org.junit.jupiter.api.Assertions.*;

public class StreamInfoTests {
    static String json = dataAsString("StreamInfo.json");

    @Test
    public void testStreamInfo() {
        StreamInfo si = new StreamInfo(getDataMessage(json));
        validateStreamInfo(si);
        assertNotNull(si.toString()); // coverage
    }

    private void validateStreamInfo(StreamInfo si) {
        ZonedDateTime zdt = DateTimeUtils.parseDateTime("2021-01-25T20:09:10.6225191Z");
        assertEquals(zdt, si.getCreateTime());

        StreamConfiguration sc = si.getConfiguration();
        assertEquals("streamName", sc.getName());
        assertEquals(3, sc.getSubjects().size());
        assertEquals("sub0", sc.getSubjects().get(0));
        assertEquals("sub1", sc.getSubjects().get(1));
        assertEquals("x.>", sc.getSubjects().get(2));

        assertEquals(RetentionPolicy.Limits, sc.getRetentionPolicy());
        assertEquals(DiscardPolicy.Old, sc.getDiscardPolicy());
        assertEquals(StorageType.Memory, sc.getStorageType());

        assertNotNull(si.getConfiguration());
        assertNotNull(si.getStreamState());
        assertEquals(1, sc.getMaxConsumers());
        assertEquals(2, sc.getMaxMsgs());
        assertEquals(3, sc.getMaxBytes());
        assertEquals(4, sc.getMaxMsgSize());
        assertEquals(5, sc.getReplicas());

        assertEquals(Duration.ofSeconds(100), sc.getMaxAge());
        assertEquals(Duration.ofSeconds(120), sc.getDuplicateWindow());

        StreamState ss = si.getStreamState();
        assertEquals(11, ss.getMsgCount());
        assertEquals(12, ss.getByteCount());
        assertEquals(13, ss.getFirstSequence());
        assertEquals(14, ss.getLastSequence());

        assertEquals(15, ss.getConsumerCount());
        assertEquals(3, ss.getSubjectCount());
        assertEquals(3, ss.getSubjects().size());

        Map<String, Subject> map = new HashMap<>();
        for (Subject su : ss.getSubjects()) {
            map.put(su.getName(), su);
        }

        Subject s = map.get("sub0");
        assertNotNull(s);
        assertEquals(1, s.getCount());

        s = map.get("sub1");
        assertNotNull(s);
        assertEquals(2, s.getCount());

        s = map.get("x.foo");
        assertNotNull(s);
        assertEquals(3, s.getCount());

        s = map.get("hmmm");
        assertNull(s);

        assertEquals(6, ss.getDeletedCount());
        assertEquals(6, ss.getDeleted().size());
        for (long x = 91; x <= 96; x++) {
            assertTrue(ss.getDeleted().contains(x));
        }

        LostStreamData lost = ss.getLostStreamData();
        assertNotNull(lost);
        assertNotNull(lost.toString()); // coverage
        assertEquals(3, lost.getMessages().size());
        for (long x = 101; x <= 103; x++) {
            assertTrue(lost.getMessages().contains(x));
        }
        assertEquals(104, lost.getBytes());

        assertEquals(DateTimeUtils.parseDateTime("0001-01-01T00:00:00Z"), ss.getFirstTime());
        assertEquals(DateTimeUtils.parseDateTime("0002-01-01T00:00:00Z"), ss.getLastTime());

        Placement pl = si.getConfiguration().getPlacement();
        assertNotNull(pl);
        assertEquals("placementclstr", pl.getCluster());
        assertEquals(2, pl.getTags().size());
        assertEquals("ptag1", pl.getTags().get(0));
        assertEquals("ptag2", pl.getTags().get(1));

        ClusterInfo cli = si.getClusterInfo();
        assertNotNull(cli.toString()); // coverage
        assertNotNull(cli);
        assertEquals("clustername", cli.getName());
        assertEquals("clusterleader", cli.getLeader());

        assertEquals(2, cli.getReplicas().size());
        assertNotNull(cli.getReplicas().get(0).toString()); // coverage
        assertEquals("name0", cli.getReplicas().get(0).getName());
        assertTrue(cli.getReplicas().get(0).isCurrent());
        assertTrue(cli.getReplicas().get(0).isOffline());
        assertEquals(Duration.ofNanos(230000000000L), cli.getReplicas().get(0).getActive());
        assertEquals(3, cli.getReplicas().get(0).getLag());

        assertEquals("name1", cli.getReplicas().get(1).getName());
        assertFalse(cli.getReplicas().get(1).isCurrent());
        assertFalse(cli.getReplicas().get(1).isOffline());
        assertEquals(Duration.ofNanos(240000000000L), cli.getReplicas().get(1).getActive());
        assertEquals(4, cli.getReplicas().get(1).getLag());

        MirrorInfo mi = si.getMirrorInfo();
        assertNotNull(mi.toString()); // coverage
        assertNotNull(mi);
        assertEquals("mname", mi.getName());
        assertEquals(16, mi.getLag());
        assertEquals(Duration.ofNanos(160000000000L), mi.getActive());
        assertNull(mi.getError());
        External e = mi.getExternal();
        assertNotNull(e);
        assertNotNull(e.toString()); // coverage
        assertEquals("api16", e.getApi());
        assertEquals("dlvr16", e.getDeliver());

        assertEquals(2, si.getSourceInfos().size());

        SourceInfo sisi = si.getSourceInfos().get(0);
        assertNotNull(sisi.toString()); // coverage
        assertEquals("sname17", sisi.getName());
        assertEquals(17, sisi.getLag());
        assertEquals(Duration.ofNanos(170000000000L), sisi.getActive());
        e = sisi.getExternal();
        assertNotNull(e);
        assertEquals("api17", e.getApi());
        assertEquals("dlvr17", e.getDeliver());

        sisi = si.getSourceInfos().get(1);
        assertEquals("sname18", sisi.getName());
        assertEquals(18, sisi.getLag());
        assertEquals(Duration.ofNanos(180000000000L), sisi.getActive());
        e = sisi.getExternal();
        assertNotNull(e);
        assertEquals("api18", e.getApi());
        assertEquals("dlvr18", e.getDeliver());

        si = new StreamInfo(JsonValue.EMPTY_MAP);
        assertNull(si.getCreateTime());
        assertNotNull(si.getStreamState());
        assertNotNull(si.getConfiguration());
        assertNull(si.getConfiguration().getPlacement());
        assertNull(si.getClusterInfo());
        assertNull(si.getMirrorInfo());
        assertNull(si.getSourceInfos());

        assertNull(Replica.optionalListOf(null));
        assertNull(Replica.optionalListOf(JsonValue.NULL));
        assertNull(Replica.optionalListOf(JsonValue.EMPTY_ARRAY));
    }

    @Test
    public void testStreamInfoOptionsCoverage() {
        StreamInfoOptions opts = StreamInfoOptions.filterSubjects("sub");
        String json = opts.toJson();
        assertTrue(json.contains(SUBJECTS_FILTER));
        assertTrue(json.contains("sub"));
        assertFalse(json.contains(DELETED_DETAILS));

        opts = StreamInfoOptions.allSubjects();
        json = opts.toJson();
        assertTrue(json.contains(SUBJECTS_FILTER));
        assertTrue(json.contains(">"));
        assertFalse(json.contains(DELETED_DETAILS));

        opts = StreamInfoOptions.deletedDetails();
        json = opts.toJson();
        assertTrue(json.contains(DELETED_DETAILS));
        assertTrue(json.contains("true"));
        assertFalse(json.contains(SUBJECTS_FILTER));
    }

    @Test
    public void testSubjectGetList() {
        List<Subject> list = Subject.listOf(null);
        assertTrue(list.isEmpty());

        list = Subject.listOf(JsonValue.NULL);
        assertTrue(list.isEmpty());

        list = Subject.listOf(JsonValue.EMPTY_MAP);
        assertTrue(list.isEmpty());

        list = Subject.listOf(JsonParser.parseUnchecked("{\"sub0\": 1, \"sub1\": 2,\"x.foo\": 3}"));
        assertEquals(3, list.size());
        assertNotNull(list.get(0).toString()); // coverage
        Collections.sort(list);
        assertEquals("sub0", list.get(0).getName());
        assertEquals("sub1", list.get(1).getName());
        assertEquals("x.foo", list.get(2).getName());
        assertEquals(1, list.get(0).getCount());
        assertEquals(2, list.get(1).getCount());
        assertEquals(3, list.get(2).getCount());
    }
}
