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
package org.apache.lucene.classification;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;

/**
 * A k-Nearest Neighbor classifier (see <code>http://en.wikipedia.org/wiki/K-nearest_neighbors</code>) based
 * on {@link MoreLikeThis}
 *
 * @lucene.experimental
 */
public class KNearestNeighborClassifier implements Classifier<BytesRef> {

  private MoreLikeThis mlt;
  private String[] textFieldNames;
  private String classFieldName;
  private IndexSearcher indexSearcher;
  private final int k;
  private Query query;

  private int minDocsFreq;
  private int minTermFreq;

  /**
   * Create a {@link Classifier} using kNN algorithm
   *
   * @param k the number of neighbors to analyze as an <code>int</code>
   */
  public KNearestNeighborClassifier(int k) {
    this.k = k;
  }

  /**
   * Create a {@link Classifier} using kNN algorithm
   *
   * @param k           the number of neighbors to analyze as an <code>int</code>
   * @param minDocsFreq the minimum number of docs frequency for MLT to be set with {@link MoreLikeThis#setMinDocFreq(int)}
   * @param minTermFreq the minimum number of term frequency for MLT to be set with {@link MoreLikeThis#setMinTermFreq(int)}
   */
  public KNearestNeighborClassifier(int k, int minDocsFreq, int minTermFreq) {
    this.k = k;
    this.minDocsFreq = minDocsFreq;
    this.minTermFreq = minTermFreq;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClassificationResult<BytesRef> assignClass(String text) throws IOException {
    TopDocs topDocs = knnSearch(text);
    List<ClassificationResult<BytesRef>> doclist = buildListFromTopDocs(topDocs);
    ClassificationResult<BytesRef> retval = null;
    double maxscore = -Double.MAX_VALUE;
    for (ClassificationResult<BytesRef> element : doclist) {
      if (element.getScore() > maxscore) {
        retval = element;
        maxscore = element.getScore();
      }
    }
    return retval;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<ClassificationResult<BytesRef>> getClasses(String text) throws IOException {
    TopDocs topDocs = knnSearch(text);
    List<ClassificationResult<BytesRef>> doclist = buildListFromTopDocs(topDocs);
    Collections.sort(doclist);
    return doclist;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<ClassificationResult<BytesRef>> getClasses(String text, int max) throws IOException {
    TopDocs topDocs = knnSearch(text);
    List<ClassificationResult<BytesRef>> doclist = buildListFromTopDocs(topDocs);
    Collections.sort(doclist);
    return doclist.subList(0, max);
  }

  private TopDocs knnSearch(String text) throws IOException {
    if (mlt == null) {
      throw new IOException("You must first call Classifier#train");
    }
    BooleanQuery mltQuery = new BooleanQuery();
    for (String textFieldName : textFieldNames) {
      mltQuery.add(new BooleanClause(mlt.like(textFieldName, new StringReader(text)), BooleanClause.Occur.SHOULD));
    }
    Query classFieldQuery = new WildcardQuery(new Term(classFieldName, "*"));
    mltQuery.add(new BooleanClause(classFieldQuery, BooleanClause.Occur.MUST));
    if (query != null) {
      mltQuery.add(query, BooleanClause.Occur.MUST);
    }
    return indexSearcher.search(mltQuery, k);
  }

  private List<ClassificationResult<BytesRef>> buildListFromTopDocs(TopDocs topDocs) throws IOException {
    Map<BytesRef, Integer> classCounts = new HashMap<>();
    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
      BytesRef cl = new BytesRef(indexSearcher.doc(scoreDoc.doc).getField(classFieldName).stringValue());
      Integer count = classCounts.get(cl);
      if (count != null) {
        classCounts.put(cl, count + 1);
      } else {
        classCounts.put(cl, 1);
      }
    }
    List<ClassificationResult<BytesRef>> returnList = new ArrayList<>();
    int sumdoc = 0;
    for (Map.Entry<BytesRef, Integer> entry : classCounts.entrySet()) {
      Integer count = entry.getValue();
      returnList.add(new ClassificationResult<>(entry.getKey().clone(), count / (double) k));
      sumdoc += count;
    }

    //correction
    if (sumdoc < k) {
      for (ClassificationResult<BytesRef> cr : returnList) {
        cr.setScore(cr.getScore() * (double) k / (double) sumdoc);
      }
    }
    return returnList;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void train(LeafReader leafReader, String textFieldName, String classFieldName, Analyzer analyzer) throws IOException {
    train(leafReader, textFieldName, classFieldName, analyzer, null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void train(LeafReader leafReader, String textFieldName, String classFieldName, Analyzer analyzer, Query query) throws IOException {
    train(leafReader, new String[]{textFieldName}, classFieldName, analyzer, query);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void train(LeafReader leafReader, String[] textFieldNames, String classFieldName, Analyzer analyzer, Query query) throws IOException {
    this.textFieldNames = textFieldNames;
    this.classFieldName = classFieldName;
    mlt = new MoreLikeThis(leafReader);
    mlt.setAnalyzer(analyzer);
    mlt.setFieldNames(textFieldNames);
    indexSearcher = new IndexSearcher(leafReader);
    if (minDocsFreq > 0) {
      mlt.setMinDocFreq(minDocsFreq);
    }
    if (minTermFreq > 0) {
      mlt.setMinTermFreq(minTermFreq);
    }
    this.query = query;
  }
}
