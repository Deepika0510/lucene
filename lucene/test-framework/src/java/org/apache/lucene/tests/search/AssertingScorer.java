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
package org.apache.lucene.tests.search;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FilterDocIdSetIterator;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.util.FixedBitSet;

/** Wraps a Scorer with additional checks */
public class AssertingScorer extends Scorer {

  enum IteratorState {
    APPROXIMATING,
    ITERATING,
    SHALLOW_ADVANCING,
    FINISHED
  };

  public static Scorer wrap(
      Random random, Scorer other, ScoreMode scoreMode, boolean canCallMinCompetitiveScore) {
    if (other == null) {
      return null;
    }
    return new AssertingScorer(random, other, scoreMode, canCallMinCompetitiveScore);
  }

  final Random random;
  final Scorer in;
  final ScoreMode scoreMode;
  final boolean canCallMinCompetitiveScore;

  IteratorState state = IteratorState.ITERATING;
  int doc;
  float minCompetitiveScore = 0;
  int lastShallowTarget = -1;

  private AssertingScorer(
      Random random, Scorer in, ScoreMode scoreMode, boolean canCallMinCompetitiveScore) {
    this.random = random;
    this.in = in;
    this.scoreMode = scoreMode;
    this.doc = in.docID();
    this.canCallMinCompetitiveScore = canCallMinCompetitiveScore;
  }

  public Scorer getIn() {
    return in;
  }

  boolean iterating() {
    // we cannot assert that state == ITERATING because of CachingScorerWrapper
    switch (docID()) {
      case -1:
      case DocIdSetIterator.NO_MORE_DOCS:
        return false;
      default:
        return state == IteratorState.ITERATING;
    }
  }

  @Override
  public void setMinCompetitiveScore(float score) throws IOException {
    assert scoreMode == ScoreMode.TOP_SCORES;
    assert canCallMinCompetitiveScore;
    assert Float.isNaN(score) == false;
    assert score >= minCompetitiveScore;
    in.setMinCompetitiveScore(score);
    minCompetitiveScore = score;
  }

  @Override
  public int advanceShallow(int target) throws IOException {
    assert scoreMode.needsScores();
    assert target >= lastShallowTarget
        : "called on decreasing targets: target = "
            + target
            + " < last target = "
            + lastShallowTarget;
    assert target >= docID() : "target = " + target + " < docID = " + docID();
    int upTo = in.advanceShallow(target);
    assert upTo >= target : "upTo = " + upTo + " < target = " + target;
    lastShallowTarget = target;
    state = target != doc ? IteratorState.SHALLOW_ADVANCING : state;
    return upTo;
  }

  @Override
  public float getMaxScore(int upTo) throws IOException {
    assert scoreMode.needsScores();
    assert upTo >= lastShallowTarget : "uTo = " + upTo + " < last target = " + lastShallowTarget;
    assert docID() >= 0 || lastShallowTarget >= 0
        : "Cannot get max scores until the iterator is positioned or advanceShallow has been called";
    float maxScore = in.getMaxScore(upTo);
    return maxScore;
  }

  @Override
  public float score() throws IOException {
    assert scoreMode.needsScores();
    assert iterating() : state;
    final float score = in.score();
    assert !Float.isNaN(score) : "NaN score for in=" + in;
    assert lastShallowTarget == -1 || score <= getMaxScore(docID());
    assert Float.compare(score, 0f) >= 0 : score;
    return score;
  }

  @Override
  public Collection<ChildScorable> getChildren() {
    // We cannot hide that we hold a single child, else
    // collectors (e.g. ToParentBlockJoinCollector) that
    // need to walk the scorer tree will miss/skip the
    // Scorer we wrap:
    return Collections.singletonList(new ChildScorable(in, "SHOULD"));
  }

  @Override
  public int docID() {
    return in.docID();
  }

  @Override
  public String toString() {
    return "AssertingScorer(" + in + ")";
  }

  @Override
  public DocIdSetIterator iterator() {
    final DocIdSetIterator in = this.in.iterator();
    assert in != null;
    return new FilterDocIdSetIterator(in) {

      @Override
      public int docID() {
        assert AssertingScorer.this.in.docID() == in.docID();
        return in.docID();
      }

      @Override
      public int nextDoc() throws IOException {
        assert state != IteratorState.FINISHED : "nextDoc() called after NO_MORE_DOCS";
        assert docID() + 1 >= lastShallowTarget;
        int nextDoc = in.nextDoc();
        assert nextDoc > doc : "backwards nextDoc from " + doc + " to " + nextDoc + " " + in;
        if (nextDoc == DocIdSetIterator.NO_MORE_DOCS) {
          state = IteratorState.FINISHED;
        } else {
          state = IteratorState.ITERATING;
        }
        assert in.docID() == nextDoc;
        assert AssertingScorer.this.in.docID() == in.docID();
        return doc = nextDoc;
      }

      @Override
      public int advance(int target) throws IOException {
        assert state != IteratorState.FINISHED : "advance() called after NO_MORE_DOCS";
        assert target > doc : "target must be > docID(), got " + target + " <= " + doc;
        assert target >= lastShallowTarget;
        int advanced = in.advance(target);
        assert advanced >= target : "backwards advance from: " + target + " to: " + advanced;
        if (advanced == DocIdSetIterator.NO_MORE_DOCS) {
          state = IteratorState.FINISHED;
        } else {
          state = IteratorState.ITERATING;
        }
        assert in.docID() == advanced : in.docID() + " != " + advanced + " in " + in;
        assert AssertingScorer.this.in.docID() == in.docID();
        return doc = advanced;
      }

      @Override
      public void intoBitSet(int upTo, FixedBitSet bitSet, int offset) throws IOException {
        assert docID() != -1;
        assert offset <= docID();
        in.intoBitSet(upTo, bitSet, offset);
        assert docID() >= upTo;
      }

      @Override
      public int docIDRunEnd() throws IOException {
        assert state == IteratorState.ITERATING;
        int nextNonMatchingDocID = in.docIDRunEnd();
        assert nextNonMatchingDocID > docID();
        return nextNonMatchingDocID;
      }
    };
  }

  @Override
  public TwoPhaseIterator twoPhaseIterator() {
    final TwoPhaseIterator in = this.in.twoPhaseIterator();
    if (in == null) {
      return null;
    }
    final DocIdSetIterator inApproximation = in.approximation();
    assert inApproximation.docID() == doc;
    final DocIdSetIterator assertingApproximation =
        new FilterDocIdSetIterator(inApproximation) {

          @Override
          public int nextDoc() throws IOException {
            assert state != IteratorState.FINISHED : "advance() called after NO_MORE_DOCS";
            assert docID() + 1 >= lastShallowTarget;
            final int nextDoc = inApproximation.nextDoc();
            assert nextDoc > doc : "backwards advance from: " + doc + " to: " + nextDoc;
            if (nextDoc == NO_MORE_DOCS) {
              state = IteratorState.FINISHED;
            } else {
              state = IteratorState.APPROXIMATING;
            }
            assert inApproximation.docID() == nextDoc;
            return doc = nextDoc;
          }

          @Override
          public int advance(int target) throws IOException {
            assert state != IteratorState.FINISHED : "advance() called after NO_MORE_DOCS";
            assert target > doc : "target must be > docID(), got " + target + " <= " + doc;
            assert target >= lastShallowTarget;
            final int advanced = inApproximation.advance(target);
            assert advanced >= target : "backwards advance from: " + target + " to: " + advanced;
            if (advanced == NO_MORE_DOCS) {
              state = IteratorState.FINISHED;
            } else {
              state = IteratorState.APPROXIMATING;
            }
            assert inApproximation.docID() == advanced;
            return doc = advanced;
          }
        };
    return new TwoPhaseIterator(assertingApproximation) {
      @Override
      public boolean matches() throws IOException {
        assert state == IteratorState.APPROXIMATING : state;
        final boolean matches = in.matches();
        if (matches) {
          assert AssertingScorer.this.in.iterator().docID() == inApproximation.docID()
              : "Approximation and scorer don't advance synchronously";
          doc = inApproximation.docID();
          state = IteratorState.ITERATING;
        }
        return matches;
      }

      @Override
      public float matchCost() {
        float matchCost = in.matchCost();
        assert !Float.isNaN(matchCost);
        assert matchCost >= 0;
        return matchCost;
      }

      @Override
      public String toString() {
        return "AssertingScorer@asTwoPhaseIterator(" + in + ")";
      }
    };
  }
}
