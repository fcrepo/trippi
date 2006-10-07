package org.trippi.impl.base;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.jrdf.graph.Triple;
import org.trippi.TripleIterator;
import org.trippi.TriplePattern;
import org.trippi.TrippiException;
import org.trippi.TupleIterator;

/**
 * A <code>TripleIterator</code> that wraps a <code>TupleIterator</code>,
 * using an array of <code>TriplePattern</code>s to generate triples
 * for each tuple.
 *
 * @author cwilper@cs.cornell.edu
 */
public class TupleBasedTripleIterator extends TripleIterator {

    private static final Logger logger =
        Logger.getLogger(TupleBasedTripleIterator.class.getName());

    private TupleIterator m_tuples;
    private TriplePattern[] m_patterns;

    private boolean m_closed;

    private Triple m_next;
    private List m_triplesFromTuples;

    public TupleBasedTripleIterator (TupleIterator tuples,
                                     TriplePattern[] patterns) throws TrippiException {
        m_tuples = tuples;
        m_patterns = patterns;
        m_closed = false;
        m_triplesFromTuples = new ArrayList();
        try {
            m_next = getNext();
        } catch (TrippiException e) {
            close();
            throw e;
        }
    }

    // return null if there are no more
    private Triple getNext() throws TrippiException {
        while (m_triplesFromTuples.size() == 0) {
            if (!m_tuples.hasNext()) return null;
            m_triplesFromTuples = m_tuples.nextTriples(m_patterns);
        }
        return (Triple) m_triplesFromTuples.remove(0);
    }

    public boolean hasNext() {
        return (m_next != null);
    }
    
    public Triple next() throws TrippiException {
        if (m_next == null) {
            return null;
        }
        Triple last = m_next;
        m_next = getNext();
        return last;
    }

    public void close() throws TrippiException {
        if (!m_closed) {
            m_tuples.close();
            m_closed = true;
        }
    }

    public void finalize() throws TrippiException {
        close();
    }

}
