/*
 * COPIED FROM APACHE LUCENE 4.7.2
 *
 * Git URL: git@github.com:apache/lucene.git, tag: releases/lucene-solr/4.7.2, path: lucene/core/src/java
 *
 * (see https://issues.apache.org/jira/browse/OAK-10786 for details)
 */

package org.apache.lucene.search;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.index.AtomicReaderContext;

/**
 * Just counts the total number of hits.
 */

public class TotalHitCountCollector extends Collector {
  private int totalHits;

  /** Returns how many hits matched the search. */
  public int getTotalHits() {
    return totalHits;
  }

  @Override
  public void setScorer(Scorer scorer) {
  }

  @Override
  public void collect(int doc) {
    totalHits++;
  }

  @Override
  public void setNextReader(AtomicReaderContext context) {
  }

  @Override
  public boolean acceptsDocsOutOfOrder() {
    return true;
  }
}
