/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.upgrade.cli.blob;

import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.blob.FileBlobStore;

import org.apache.jackrabbit.guava.common.io.Closer;

public class FileBlobStoreFactory implements BlobStoreFactory {

    private final String directory;

    public FileBlobStoreFactory(String directory) {
        this.directory = directory;
    }

    @Override
    public BlobStore create(Closer closer) {
        return new FileBlobStore(directory);
    }

    @Override
    public String toString() {
        return String.format("FileBlobStore[%s]", directory);
    }
}
