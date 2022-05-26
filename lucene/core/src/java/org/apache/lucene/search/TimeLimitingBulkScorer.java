package org.apache.lucene.search;

import org.apache.lucene.index.QueryTimeoutImpl;
import org.apache.lucene.util.Bits;
import java.io.IOException;

public class TimeLimitingBulkScorer extends BulkScorer {

    public static class TimeExceededException extends RuntimeException {
        private TimeExceededException() {
            super("TimeLimit Exceeded");
        }
    }

    private BulkScorer in;
    private QueryTimeoutImpl queryTimeout;

    public TimeLimitingBulkScorer(BulkScorer bulkScorer, QueryTimeoutImpl queryTimeout) {
        this.in = bulkScorer;
        this.queryTimeout = queryTimeout;
    }

    @Override
    public int score(LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
        int interval = 4096;
        while (min < max) {
            final int newMax = (int) Math.min((long) min + interval, max);
            if (queryTimeout.shouldExit() == true) {
                throw new TimeLimitingBulkScorer.TimeExceededException();
            }
            min = in.score(collector, acceptDocs, min, newMax); // in is the wrapped bulk scorer
        }
        return min;
    }

    @Override
    public long cost() {
        return 0;
    }
}