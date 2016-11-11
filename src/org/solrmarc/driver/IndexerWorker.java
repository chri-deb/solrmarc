package org.solrmarc.driver;

import java.util.AbstractMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.marc4j.marc.Record;
import org.solrmarc.driver.Indexer.eErrorHandleVal;
import org.solrmarc.index.indexer.IndexerSpecException.eErrorSeverity;
import org.solrmarc.tools.SolrMarcIndexerException;

public class IndexerWorker implements Runnable
{
    private final static Logger logger = Logger.getLogger(IndexerWorker.class);
    private AtomicInteger cnts[];
    private final BlockingQueue<AbstractMap.SimpleEntry<Integer, Record>> readQ;
    private final BlockingQueue<RecordAndDoc> docQ;
    private Indexer indexer;
    private MarcReaderThread readerThread;
    private int threadCount;
    private boolean doneWorking = false;
    private boolean interrupted = false;

    public IndexerWorker(MarcReaderThread readerThread, BlockingQueue<AbstractMap.SimpleEntry<Integer, Record>> readQ, BlockingQueue<RecordAndDoc> docQ, Indexer indexer, AtomicInteger cnts[], int threadCount)
    {
        this.readerThread = readerThread;
        this.readQ = readQ;
        this.docQ = docQ;
        this.indexer = indexer;
        this.cnts = cnts;
        this.threadCount = threadCount;
        this.doneWorking = false;
    }

    public boolean isDoneWorking()
    {
        return doneWorking;
    }

    public void setInterrupted()
    {
        interrupted = true;
    }

    public boolean isInterrupted()
    {
        return(interrupted);
    }

    @Override
    public void run()
    {
        // if this isn't the first Indexer Worker Thread make a thread safe instance duplicate of the indexer
        // this primarily means making a new instance object for each External Method class
        if (threadCount > 0)
        {
            indexer = (threadCount == 0) ? indexer : indexer.makeThreadSafeCopy();
        }
        Thread.currentThread().setName("RecordIndexer-Thread-"+threadCount);
        while ((! readerThread.isDoneReading(false) || !readQ.isEmpty()) && !readerThread.isInterrupted() && !isInterrupted() )
        {
            try
            {
                AbstractMap.SimpleEntry<Integer, Record> pair = readQ.poll(10, TimeUnit.MILLISECONDS);
                if (pair == null)  continue;
                int count = pair.getKey();
                Record rec = pair.getValue();

//                System.out.println(rec.getControlNumber() + " :  read in thread "+ threadCount);
//                long id = Long.parseLong(rec.getControlNumber().substring(1))* 100 + threadCount;
//                rec.setId((long)id);
                if (isInterrupted())  break;
                RecordAndDoc recDoc = indexer.indexToSolrDoc(rec);
                if (isInterrupted())  break;
                if (recDoc.getSolrMarcIndexerException() != null)
                {
                    SolrMarcIndexerException smie = recDoc.getSolrMarcIndexerException();
                    String recCtrlNum = recDoc.rec.getControlNumber();
                    String idMessage = smie.getMessage() != null ? smie.getMessage() : "";
                    if (smie.getLevel() == SolrMarcIndexerException.IGNORE)
                    {
                        logger.info("Record will be Ignored " + (recCtrlNum != null ? recCtrlNum : "") + " " + idMessage + " (record count " + count + ")");
                        continue;
                    }
                    else if (smie.getLevel() == SolrMarcIndexerException.DELETE)
                    {
                        logger.info("Record will be Deleted " + (recCtrlNum != null ? recCtrlNum : "") + " " + idMessage + " (record count " + count + ")");
                        indexer.delQ.add(recCtrlNum);
                        continue;
                    }
                    else if (smie.getLevel() == SolrMarcIndexerException.EXIT)
                    {
                        logger.info("Serious Error flagged in record " + (recCtrlNum != null ? recCtrlNum : "") + " " + idMessage + " (record count " + count + ")");
                        logger.info("Terminating indexing.");
                        indexer.shutDown(false);
                        break;
                    }
                }
                if (recDoc.getErrLvl() != eErrorSeverity.NONE)
                {
                    if (indexer.isSet(eErrorHandleVal.RETURN_ERROR_RECORDS) && !indexer.isSet(eErrorHandleVal.INDEX_ERROR_RECORDS))
                    {
                        indexer.errQ.add(recDoc);
                    }
                    if (recDoc.getErrLvl() == eErrorSeverity.FATAL && recDoc.ise != null)
                    {
                        String recCtrlNum = recDoc.rec.getControlNumber();
                        String idMessage = recDoc.ise.getMessage() != null ? recDoc.ise.getMessage() : "";
                        String indSpec = recDoc.ise.getSpecMessage() != null ? recDoc.ise.getSpecMessage() : "";
                        logger.info("Fatal Error returned for record " + (recCtrlNum != null ? recCtrlNum : "") + " : " + idMessage + " (record count " + count + ")");
                        logger.info("Fatal Error from by index spec  " + (recCtrlNum != null ? recCtrlNum : "") + " : " + indSpec + " (record count " + count + ")");
                        logger.info("Terminating indexing.");
                        indexer.shutDown(false);
                        break;
                    }
                    if (!indexer.isSet(eErrorHandleVal.INDEX_ERROR_RECORDS))
                    {
                        logger.debug("Skipping error record: " + recDoc.rec.getControlNumber());
                        continue;
                    }
                }
                if (isInterrupted())  break;
                boolean offerWorked = docQ.offer(recDoc);
                while (!offerWorked)
                {
                    try {
                        synchronized (docQ) { docQ.wait(); }

                        offerWorked = docQ.offer(recDoc);
                    }
                    catch (InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (offerWorked) cnts[1].incrementAndGet();
            }
            catch (InterruptedException e)
            {
                logger.warn("Interrupted while waiting for a record to appear in the read queue.");
                interrupted = true;
                Thread.currentThread().interrupt();
            }
        }
        doneWorking = true;
    }
}
