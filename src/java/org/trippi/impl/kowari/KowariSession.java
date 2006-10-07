package org.trippi.impl.kowari;

import java.io.*;
import java.net.URI;
import java.text.*;
import java.util.*;

import org.apache.log4j.Logger;
import org.jrdf.graph.*;
import org.kowari.client.jrdf.AbstractGraphFactory;
import org.kowari.itql.ItqlInterpreter;
import org.kowari.query.Answer;
import org.kowari.server.Session;
import org.kowari.store.DatabaseSession;
import org.kowari.store.jena.KowariQueryEngine;
import org.kowari.store.jena.RdqlQuery;
import org.kowari.store.jrdf.JRDFGraph;
import org.trippi.RDFUtil;
import org.trippi.TripleIterator;
import org.trippi.TrippiException;
import org.trippi.TupleIterator;
import org.trippi.impl.base.AliasManager;
import org.trippi.impl.base.TriplestoreSession;
import org.trippi.impl.jena.JenaTupleIterator;

import com.hp.hpl.jena.rdql.QueryExecution;
import com.hp.hpl.jena.rdql.QueryResults;

/**
 * A <code>TriplestoreSession</code> that wraps a Kowari session and
 * an associated Jena Model (for RDQL query support).
 *
 * @author cwilper@cs.cornell.edu
 */
public class KowariSession implements TriplestoreSession {

    private static final String cName = KowariSession.class.getName();

    private static final Logger logger = Logger.getLogger(cName);

    private static final String rName = cName + ".replay.";

    private static final Logger addLogger = Logger.getLogger(rName + "add");
    private static final Logger delLogger = Logger.getLogger(rName + "del");
    private static final Logger itqLogger = Logger.getLogger(rName + "itq");
    private static final Logger spoLogger = Logger.getLogger(rName + "spo");

    private Session m_session;
    private DatabaseSession m_dbSession;
    private URI m_modelURI;
    private URI m_textModelURI;

    private GraphElementFactory m_factory;

    private com.hp.hpl.jena.rdf.model.Model m_jenaModel;
    private AliasManager m_aliasManager;

    private boolean m_closed;
    private String m_serverURI;

    /**
     * Construct a local Kowari session.
     *
     * @param jenaModel the Jena Model to use for RDQL queries.  If null, this
     *                  session won't support RDQL queries.
     */
    public KowariSession(DatabaseSession dbSession,
                         URI modelURI,
                         URI textModelURI,
                         com.hp.hpl.jena.rdf.model.Model jenaModel,
                         AliasManager aliasManager) {
        m_dbSession = dbSession;
        m_session = dbSession;
        m_modelURI = modelURI;
        m_serverURI =  modelURI.toString().substring(0, m_modelURI.toString().lastIndexOf("#") + 1);
        m_textModelURI = textModelURI;
        m_jenaModel = jenaModel;
        m_aliasManager = aliasManager;
        m_closed = false;
        logger.info("Created local session.");
    }
    
    /**
     * Construct a remote Kowari session.
     * 
     * @param jenaModel the Jena Model to use for RDQL queries.  If null, this
     *                  session won't support RDQL queries.
     * @throws GraphException
     */
    public KowariSession(Session session,
            URI modelURI,
            URI textModelURI,
            com.hp.hpl.jena.rdf.model.Model jenaModel,
            AliasManager aliasManager) {
    	m_dbSession = null;
    	m_session = session;
    	m_modelURI = modelURI;
    	m_serverURI =  modelURI.toString().substring(0, m_modelURI.toString().lastIndexOf("#") + 1);
    	m_textModelURI = textModelURI;
    	m_jenaModel = jenaModel;
    	m_aliasManager = aliasManager;
    	m_closed = false;
    	logger.info("Created remote session.");
    }

    public GraphElementFactory getElementFactory() throws GraphException {
        if (m_factory == null) {
            Graph graph;
            if (m_dbSession != null) {
                graph = new JRDFGraph(m_dbSession, m_dbSession.getDatabase().getURI(), true);
            } else {
                graph = AbstractGraphFactory.createGraph(m_modelURI, m_session);
            }
            m_factory = graph.getElementFactory();
        }
        return m_factory;
    }

    public String[] listTupleLanguages() {
        return KowariSessionFactory.TUPLE_LANGUAGES;
    }

    public String[] listTripleLanguages() {
        return KowariSessionFactory.TRIPLE_LANGUAGES;
    }

    public void add(Set triples) throws TrippiException {
        doTriples(triples, true);
    }

    public void delete(Set triples) throws TrippiException {
        doTriples(triples, false);
    }

    private void doTriples(Set triples, 
                           boolean add) throws TrippiException {
        try {
            if (add) {
                if (addLogger.isDebugEnabled()) {
                    logTriples(triples, true);
                }
                m_session.insert(m_modelURI, triples);
            } else {
                if (delLogger.isDebugEnabled()) {
                    logTriples(triples, false);
                }
                m_session.delete(m_modelURI, triples);
            }
            if (m_textModelURI != null) doPlainLiteralTriples(triples, true);
            
        } catch (Exception e) {
            e.printStackTrace();
            String mod = "deleting";
            if (add) mod = "adding";
            String msg = "Error " + mod + " triples: " + e.getClass().getName();
            if (e.getMessage() != null) msg = msg + ": " + e.getMessage();
            throw new TrippiException(msg, e);
        }
    }

    private void doPlainLiteralTriples(Set triples, 
                                       boolean add) throws Exception {
        Set plainLiteralTriples = new HashSet();
        Iterator iter = triples.iterator();
        while (iter.hasNext()) {
            Triple triple = (Triple) iter.next();
            if (triple.getObject() instanceof Literal) {
                Literal literal = (Literal) triple.getObject();
                if (literal.getDatatypeURI() == null 
                        && literal.getLexicalForm().length() > 0) {
                    plainLiteralTriples.add(triple);
                }
            }
        }
        if (plainLiteralTriples.size() > 0) {
            if (add) {
                m_session.insert(m_textModelURI, plainLiteralTriples);
            } else {
                m_session.delete(m_textModelURI, plainLiteralTriples);
            }
        }
    }

    private String doAliasReplacements(String q) {
        String out = q;
        Map m = m_aliasManager.getAliasMap();
        Iterator iter = m.keySet().iterator();
        while (iter.hasNext()) {
            String alias = (String) iter.next();
            String fullForm = (String) m.get(alias);
            out = out.replaceAll("<" + alias + ":", "<" + fullForm)
                     .replaceAll("\\^\\^" + alias + ":(\\S+)", "^^<" + fullForm + "$1>");
        }
        if (!q.equals(out)) {
            logger.info("Substituted aliases, query is now: " + out);
        }
        return out;
    }

    public TripleIterator findTriples(SubjectNode subject,
                                      PredicateNode predicate,
                                      ObjectNode object) throws TrippiException {
        Answer ans = null;
        try {
            Triple triple = new RDFUtil().createTriple(subject, predicate, object);
            if (spoLogger.isDebugEnabled()) {
                logSPO(triple);
            }
            ans = m_session.find(m_modelURI, triple);
            return new KowariTripleIterator(ans);
        } catch (Exception e) {
            if (ans != null) {
                try {
                    ans.close();
                } catch (Exception e2) {
                    logger.warn("Unable to close answer.");
                }
            }
            throw new TrippiException("Unexpected error while finding triples.", e);
        }
    }

    public TripleIterator findTriples(String lang,
                                      String queryText) throws TrippiException {
        throw new TrippiException("Unsupported triple query language: " + lang);
    }

    public TupleIterator query(String queryText,
                                   String language) throws TrippiException {
        if (language.equalsIgnoreCase("itql")) {
            queryText = doAliasReplacements(queryText);
            Answer ans = null;
            try {
                // expand shortcut "from <#" to "from <" + m_serverURI
                queryText = queryText.replaceAll("\\s+from\\s+<#", " from <" + m_serverURI); 
                // expand shortcut "in <#" to "in <" + m_serverURI
                queryText = queryText.replaceAll("\\s+in\\s+<#", " in <" + m_serverURI); 
                ItqlInterpreter interpreter = new ItqlInterpreter(new HashMap());
                if (itqLogger.isDebugEnabled()) {
                    logITQ(queryText);
                }
                ans = m_session.query(interpreter.parseQuery(queryText));
                return new KowariTupleIterator(ans);
            } catch (TrippiException e) {
                throw e;
            } catch (Exception e) {
                if (ans != null) {
                    try {
                        ans.close();
                    } catch (Exception e2) {
                        logger.warn("Unable to close answer.");
                    }
                }
                String msg = "ITQL query failed";
                if (e.getMessage() != null) msg = msg + ": " + e.getMessage();
                throw new TrippiException(msg, e);
            }
        } else if (language.equalsIgnoreCase("rdql")) {
            queryText = doAliasReplacements(queryText);
            if (m_jenaModel != null) {
                RdqlQuery q = new RdqlQuery(queryText);
                q.setSource(m_jenaModel);
/*
                Map m = m_aliasManager.getAliasMap();
                Iterator iter = m.keySet().iterator();
                while (iter.hasNext()) {
                    String alias = (String) iter.next();
                    String uriPrefix = (String) m.get(alias);
                    q.setPrefix(alias, uriPrefix);
                }
*/

// FIXME: Ensure Jena's unchecked exceptions are wrapped by checked ones
                QueryExecution qe = new KowariQueryEngine(q);
                QueryResults results = qe.exec();
                return new JenaTupleIterator(results);
            } else {
                throw new TrippiException("This KowariSession does not "
                        + "support rdql.");
            }
        } else {
            throw new TrippiException("Unrecognized query language: " 
                    + language);
        }
    }

    public void close() throws TrippiException {
        if ( !m_closed ) {
            try {
                m_session.close();
            } catch (Exception e) {
                String msg = "Error closing KowariSession: " 
                    + e.getClass().getName();
                if (e.getMessage() != null) msg = msg + ": " + e.getMessage();
                throw new TrippiException(msg, e); 
            } finally {
                if (m_jenaModel != null) {
                    try {
                        m_jenaModel.close();
                    } catch (Exception e) {
                        String msg = "Error closing Jena Model for Kowari "
                                + "Session: " + e.getClass().getName();
                        if (e.getMessage() != null) {
                            msg = msg + ": " + e.getMessage();
                        }
                        throw new TrippiException(msg, e);
                    }
                }
                m_closed = true;
                logger.info("Closed session.");
            }
        }
    }

    /**
     * Ensure close() gets called at garbage collection time.
     */
    public void finalize() throws TrippiException {
        close();
    }

    private void logTriples(Set triples, boolean add) {
        Logger tripleLogger;
        if (add) {
            tripleLogger = addLogger;
        } else {
            tripleLogger = delLogger;
        }
        StringBuffer buf = new StringBuffer();
        Iterator iter = triples.iterator();
        int i = 0;
        while (iter.hasNext()) {
            if (i > 0) buf.append("\n");
            i++;
            buf.append(toString((Triple) iter.next()));
        }
        tripleLogger.debug(buf.toString());
    }

    private void logSPO(Triple triple) {
        spoLogger.debug(toString(triple));
    }

    private void logITQ(String queryText) {
        itqLogger.debug(queryText);
    }

    private static String toString(Triple triple) {
        return toString(triple.getSubject()) + " "
             + toString(triple.getPredicate()) + " "
             + toString(triple.getObject());
    }

    public static String toString(Node node) {
        if (node == null) return "*";
        if (node instanceof URIReference) {
            URIReference n = (URIReference) node;
            return "<" + n.getURI().toString() + ">";
        } else if (node instanceof BlankNode) {
            return "_node" + node.hashCode();
        } else {
            Literal n = (Literal) node;
            StringBuffer out = new StringBuffer();
            out.append("\"" + escapeLiteral(n.getLexicalForm()) + "\"");
            if (n.getLanguage() != null) {
                out.append("@" + n.getLanguage());
            } else if (n.getDatatypeURI() != null) {
                out.append("^^" + n.getDatatypeURI().toString());
            }
            return out.toString();
        }
    }

    private static String escapeLiteral(String s) {
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ( c == '"' ) {
                out.append("\\\"");
            } else if ( c == '\\' ) {
                out.append("\\\\");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

}
