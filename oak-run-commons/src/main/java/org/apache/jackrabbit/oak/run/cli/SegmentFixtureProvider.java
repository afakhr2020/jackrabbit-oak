/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.run.cli;

import java.io.File;
import java.io.IOException;

import org.apache.jackrabbit.guava.common.io.Closer;
import org.apache.jackrabbit.oak.plugins.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.plugins.segment.file.FileStore;
import org.apache.jackrabbit.oak.plugins.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.apache.jackrabbit.oak.stats.StatisticsProvider;

import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static org.apache.jackrabbit.oak.spi.whiteboard.WhiteboardUtils.getService;

@SuppressWarnings("deprecation")
class SegmentFixtureProvider {

    static NodeStore create(Options options, BlobStore blobStore, Whiteboard wb, Closer closer, boolean readOnly)
            throws IOException, InvalidFileStoreVersionException {
        StatisticsProvider statisticsProvider = requireNonNull(getService(wb, StatisticsProvider.class));

        String path = options.getOptionBean(CommonOptions.class).getStoreArg();
        FileStore.Builder builder = FileStore.builder(new File(path))
                .withMaxFileSize(256).withDefaultMemoryMapping();

        FileStoreBuilderCustomizer customizer = getService(wb, FileStoreBuilderCustomizer.class);
        if (customizer != null) {
            customizer.customize(builder);
        }

        if (blobStore != null) {
            builder.withBlobStore(blobStore);
        }

        NodeStore nodeStore;
        if (readOnly) {
            FileStore.ReadOnlyStore fileStore = builder
                    .withStatisticsProvider(statisticsProvider)
                    .buildReadOnly();
            closer.register(fileStore);
            nodeStore = SegmentNodeStore.builder(fileStore).build();
            wb.register(FileStore.ReadOnlyStore.class, fileStore, emptyMap());
        } else {
            FileStore fileStore = builder
                    .withStatisticsProvider(statisticsProvider)
                    .build();
            closer.register(fileStore);
            nodeStore = SegmentNodeStore.builder(fileStore).build();
            wb.register(FileStore.class, fileStore, emptyMap());
        }

        return nodeStore;
    }
}
