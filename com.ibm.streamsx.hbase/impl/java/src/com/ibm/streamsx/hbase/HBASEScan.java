/* Copyright (C) 2013-2014, International Business Machines Corporation  */
/* All Rights Reserved                                                 */

package com.ibm.streamsx.hbase;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.log4j.Logger;

import com.ibm.gsk.ikeyman.keystore.entry.Entry;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.meta.TupleType;
import com.ibm.streams.operator.model.Parameter;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.types.RString;

/**
 * Scan an HBASE table and output the specified tuples.
 * 
 */
@PrimitiveOperator(name="HBASEScan", namespace="com.ibm.streamsx.hbase",
description="Scan an HBASE table.  Each row/columnFamily/columnQualifer/value entry is mapped to a tuple, with the row populating the row attribute, the columnFamily populating teh columnFamily attribute, the columnQualifier attribute populating the columnQualifier attribute, and the value populating the value attribute.  The value may either be a long or a string, all other values must be rstring.  An optional start row and end row may be specified.")
@OutputPorts({@OutputPortSet(description="Tuples found", cardinality=1, optional=false, windowPunctuationOutputMode=WindowPunctuationOutputMode.Generating)})
public class HBASEScan extends HBASEOperator{

	/*
	 * Used to describe the way tuples will be populated.  It is set at initialization time, and then used on process
	 * to determine what to do.  
	 */
	private enum OutputMode {
		TUPLES,
		RECORD
	}
	
	/**
	 * Thread for calling <code>produceTuples()</code> to produce tuples 
	 */
    private Thread processThreadArray[];
    
    private OutputMode outputMode= null;
    protected String startRow = null;
    protected String endRow = null;
    static final String START_ROW_PARAM="startRow";
    static final String END_ROW_PARAM="endRow";
    static final String MAXIMUM_SCAN_THREADS="maxThreads";
    private double initDelay = 0.0;
    private int outRow = -1;
    private int outColumnF = -1;
    private int outColumnQ = -1;
    
    private String outAttrName = "value";
    private OutputMapper outMapper = null;
    
    private String resultCountAttrName = null;
    private int resultCountIndex = -1;
   
    private int maxThreads = 1;
    private int actualNumThreads = -1;
    private int numFinishedThreads = -1;
    private int channel = -1;
    private int maxChannels = 0;
    private long minTimestamp = Long.MIN_VALUE;
    private int maxVersions = -1;
    java.util.concurrent.ConcurrentLinkedQueue<Pair<byte[],byte[]>> regionQueue;
    Logger logger = Logger.getLogger(this.getClass());
    RecordOutputMapper recordPopulator = null;
    
    @Parameter(name=MAXIMUM_SCAN_THREADS,optional=true,description="Maximum number of threads to use to scan the table.  Defaults to one.")
    public void setMaximumThreads(int max) {
    	maxThreads = max;
    }
    
    @Parameter(optional=true,description="Delay, in seconds, before starting scan.")
    public void setInitDelay(double delay) {
    	initDelay=delay;
    }
    
    @Parameter(name=HBASEGet.MIN_TIMESTAMP_PARAM_NAME,optional=true,description=HBASEGet.MIN_TIMESTAMP_DESC)
    public void setMinTimestamp(long inTS) {
    	minTimestamp= inTS;
    }
    
    @Parameter(name=HBASEGet.MAX_VERSIONS_PARAM_NAME,optional=true,description=HBASEGet.MAX_VERSIONS_DESC)
    public void setMaxVersions(int inMax) {
    	maxVersions=inMax;
    }
    
    @Parameter(optional=true,description="If this operator is part of a parallel region it shares the work of scanning with other operators in the region.  To do this, this should be set by calling getChannel().  It is required if maximum number of channels is other than zero.")
    public void setChannel(int chan) {
    	channel = chan;
    }
    
    @Parameter(optional=true,description="If this operator is part of a parallel region, this should be set by calling getMaxChannels().  If the operator is in a parallel region, then the regions to be scanned will be divided among the other copies of this operator in the other channels.  You must set channel if this parameter is set.")
    public void setMaxChannels(int numChan) {
    	maxChannels = numChan;
    }
    
    @Parameter(name=HBASEGet.SUCCESS_PARAM_NAME,optional=true,description=
    		"Output attribute in which to put the number of results found.  When the result is a tuple, "+
    		"is the number attributes in that tuple that were populated.")
    public void setResultCountName(String name) {
    	resultCountAttrName = name;
    }
    
    @Parameter(name=START_ROW_PARAM,optional=true,description="Row to use to start the scan (inclusive)")
    public void setStartRow(String row) {
    	startRow = row;
    }
    
    @Parameter(name=END_ROW_PARAM,optional=true,description="Row to use to stop the scan (exclusive)")
    public void setEndRow(String row) {
    	endRow = row;
    }
    
    @Parameter(name=HBASEGet.OUT_PARAM_NAME,optional=true,description="Name of the attribute in which to put the value."
    			+"Defaults to value.  If it is a tuple type, the attribute names are used as columnQualifiers"
    			+ "if multiple families are included in the scan, and they have the ame columnQualifiers, there is no "
    			+ "way of knowing which columnFamily was used to populate a tuple attribute")
    public void setOutAttrName(String name) {
    	outAttrName = name;
    }
    
    @ContextCheck(compile=true)
    public static void checks(OperatorContextChecker checker) {
    	checker.checkDependentParameters("endRow", "startRow");
    	checker.checkDependentParameters("maxChannels","channel");
    	checker.checkDependentParameters("channel","maxChannels");
    }
    
    /**
     * Process parameters and get setup of scan.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void initialize(OperatorContext context)
    		throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
    	super.initialize(context);

    	// Now check that the output is hte proper format.
    	StreamingOutput<OutputTuple> output = getOutput(0);
    	StreamSchema outSchema = output.getStreamSchema();

    	if (resultCountAttrName != null) {
    		resultCountIndex = checkAndGetIndex(outSchema,resultCountAttrName,MetaType.INT32,true) ;
    	}
    	
    	outMapper = OutputMapper.createOutputMapper(outSchema, outAttrName,charset);
    	
    	outRow = checkAndGetIndex(outSchema,"row",false);
    	outColumnF = checkAndGetIndex(outSchema,"columnFamily",false);
    	outColumnQ = checkAndGetIndex(outSchema,"columnQualifier",false);
    	
    	if (outMapper.isCellPopulator()) {
    		outputMode = OutputMode.TUPLES;
    	}
    	else if (outMapper.isRecordPopulator()) {
    		outputMode = OutputMode.RECORD;
    	}
    	else {
    		throw new Exception("OutputMapper is neither Cell nor Record populator");
    	}
  


    	regionQueue = new ConcurrentLinkedQueue<Pair<byte[],byte[]>>();

    	byte startBytes[] = null;
    	byte endBytes[] = null;
    	
    	if (startRow != null) {
    		startBytes = startRow.getBytes(charset);
    	}
    	if (endRow != null) {
    		endBytes = endRow.getBytes(charset);
    	}

    	// Need to get the start and end keys if these aren't part of the input. 
    	Pair<byte[][],byte[][]> startEndKeys = myTable.getStartEndKeys(); 
    	if (startBytes == null) {
    		startBytes = startEndKeys.getFirst()[0];
    	}
    	if (endBytes == null) {
    		endBytes = startEndKeys.getSecond()[startEndKeys.getSecond().length-1];
    	}
    		
    	int numRegions = 0;
    	// In order to get the regions, we need to supply a startrow and an end row.
    	logger.debug("Start row: "+printBytes(startBytes)+" end row "+printBytes(endBytes));
    	
    	// Get a list of regions.  We assume the list is always the same.
    	List<HRegionLocation> regionList = myTable.getRegionsInRange(startBytes,endBytes);
    	
    	// Check that the combinatin of channel and maxChannels makes sense
    	assert((channel== -1 && maxChannels == 0) ||  // it's the default 
    			// or maxChannels is positive, and channel is between 0 and maxChannels
    			(maxChannels > 0 &&
    					channel >= 0 &&
    					channel < maxChannels));
    	
    	logger.debug("This is channel "+channel +" of "+maxChannels);
    	
    	// Now, we go through the regionList, check to see if the region is the responsibility of this 
    	// channel.  If it is, we add it to the regionQueue.   
    	for (int i = 0; i < regionList.size(); i++) {
    		if (maxChannels == 0   // if this is true, we aren't in a parallel region
    				|| i % maxChannels == channel) {    // if this is true, we're in a parallel region, and this region server is to be handled here
    			numRegions++;
    			HRegionInfo info = regionList.get(i).getRegionInfo();
    			byte[] startKey = info.getStartKey();
    			byte[] endKey = info.getEndKey();
    			// If the start row is in the region, we want to shrink the scan range, so as not to include things before that row.
    		 	if (startBytes != null && startBytes.length > 0 && info.containsRow(startBytes)) {
    		 		startKey = startBytes;
    		 	}
    		 	// If the end row is in teh region, we want to shrink the scan range, so as to to include rows after the endrow.
    		 	if (endBytes != null && endBytes.length > 0 && info.containsRow(endBytes)) {
    		 		endKey = endBytes;
    		 	}
    		 	logger.debug("Region "+i+" original range ["+new String(info.getStartKey())+","+new String(info.getEndKey())+"), changed to ["+new String(startKey)+","+new String(endKey)+")");
    			regionQueue.add(new Pair<byte[],byte[]>(startKey,endKey));
    		}
    	}
    	// The actually number of threads is the minimum of the maxThreads and the number of regions
    	actualNumThreads = Math.min(numRegions, maxThreads);
    	logger.debug("MaxThreads = "+maxThreads+" numRegions = "+numRegions+" actualNumThreads "+actualNumThreads);
    	// we use this to determine when to send punctuation.
    	numFinishedThreads = 0;
    	
    	// Let's create the threads.
    	processThreadArray = new Thread[actualNumThreads];
    	for (int i = 0; i < actualNumThreads; i++) {
    		processThreadArray[i] = getOperatorContext().getThreadFactory().newThread(
    				new Runnable() {

    					@Override
    					public void run() {
    						try {
    							produceTuples();
    						} catch (Exception e) {
    							Logger.getLogger(this.getClass()).error("Operator error", e);
    							throw new RuntimeException(e);
    						}                    
    					}

    				});
    		/*
    		 * Set the thread not to be a daemon to ensure that the SPL runtime
    		 * will wait for the thread to complete before determining the
    		 * operator is complete.
    		 */
    		processThreadArray[i].setDaemon(false);
    	}

    }
       
    private String printBytes(byte[] endBytes) {
		StringBuffer buff = new StringBuffer();
		buff.append(".");
		for (byte b : endBytes) {
		buff.append(Byte.toString(b));
		buff.append(".");
		}
		return buff.toString();
	}

	/**	
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
    	// Start a thread for producing tuples because operator 
    	// implementations must not block and must return control to the caller.
        for(Thread t : processThreadArray) {
        	t.start();
        }
    }
    
    /** Submits a punctuation when the last thread finishes.
     * This is called by each thread when it's done. It counts the number of finished threads.  When the 
     * number of finished threads is equal to the number of threads, send punctuation.
     * In this case, we send both a window marker and a final marker.  In the future, if we allow scans to 
     * be triggered by the input port, we'd send only a window marker.
     * 
     */
    private synchronized void threadFinished()  {
    	numFinishedThreads++;
    	if (numFinishedThreads == actualNumThreads) {
    		final StreamingOutput<OutputTuple> out = getOutput(0);
    		try {
				out.punctuate(Punctuation.WINDOW_MARKER);
		  		out.punctuate(Punctuation.FINAL_MARKER);
			} catch (Exception e) {
				// we don't re-throw this exception because this operator is done anyway at this point.
				logger.error("Cannot send punctation",e);
			}
  
    	}
    }
    
    /**
     * Submit new tuples to the output stream
     * @throws Exception if an error occurs while submitting a tuple
     */
    private void produceTuples() throws Exception  {
    	final StreamingOutput<OutputTuple> out = getOutput(0);

    	if (initDelay > 0.0) {
    		Thread.sleep((int)initDelay*1000);
    	}
    	if (logger.isInfoEnabled()) logger.info("Done sleeping");
    	// First check to see if there are any regions left to scan.  If not, this thread is finished.
    	Pair<byte[],byte[]> thisScan = regionQueue.poll();
    	while (thisScan != null) {
    		// Create the scan
    		Scan myScan;
    		byte startBytes[] = thisScan.getFirst();
    		byte endBytes[] = thisScan.getSecond();
    		if (startBytes != null && endBytes != null) {
    			myScan = new Scan(startBytes,endBytes);
    		}
    		else if (startBytes != null)  {
    			myScan = new Scan(startBytes);
    		}
    		else if (endRow != null) {
    			myScan = new Scan(new byte[0],endBytes);
    		}	
    		else {
    			myScan = new Scan();
    		}
    		if (maxVersions == 0) {
    			myScan.setMaxVersions();
    		}
    		else if (maxVersions >0) {
    			myScan.setMaxVersions(maxVersions);
    		}
    		
    		if (minTimestamp != Long.MIN_VALUE) {
    			myScan.setTimeRange(minTimestamp, Long.MAX_VALUE);
    		}
    		// select column families and column qualifiers
    		if (staticColumnFamilyList != null && staticColumnQualifierList != null) {

    			for (String fam: staticColumnFamilyList) {
    				for (String qual: staticColumnQualifierList) {
    					myScan.addColumn(fam.getBytes(charset), qual.getBytes(charset));
    				}
    			}
    		}
    		else if (staticColumnFamilyList != null) {
    			for (String fam: staticColumnFamilyList) {
    				myScan.addFamily(fam.getBytes(charset));
    			}
    		}
    		if (logger.isInfoEnabled()) logger.info("Scan set, processing results");
    		// Get a results scanner.
    		ResultScanner results = myTable.getScanner(myScan);
    		// Process results.
    		Result currRow = results.next();
    		while (currRow != null) {
    			String row = new String(currRow.getRow(),charset);
    			NavigableMap<byte[],NavigableMap<byte[],NavigableMap<Long,byte[]>>> allValues = currRow.getMap();
    			if (OutputMode.TUPLES == outputMode) {
    				for (byte[] family: allValues.keySet()) {
    					for (byte [] qual: allValues.get(family).keySet()) {
    						NavigableMap<Long,byte[]> thisCell = allValues.get(family).get(qual);
    						if (thisCell.isEmpty()) {
    							// If there are no values here, we don't want to output a tuple
    							continue;
    						}
    						// If we get here, we can be probably assume the map is not empty.
    						OutputTuple tuple = out.newTuple();
    						if (outRow >= 0)
    							tuple.setString(outRow,row);
    						if (outColumnF >= 0)
    							tuple.setString(outColumnF,new String(family,charset));

    						if (outColumnQ >= 0)
    							tuple.setString(outColumnQ,new String(qual,charset));
    						// Don't need to look at teh return value, because we've already determined it's non-empty.
    						outMapper.populate(tuple,thisCell);
    						if (resultCountIndex >=0) {
    							tuple.setInt(resultCountIndex,1);
    						}
    						out.submit(tuple);
    					}
    				}
    			}
    			else if (OutputMode.RECORD == outputMode) {
    				OutputTuple tuple = out.newTuple();
    				int numResults = outMapper.populateRecord(tuple, currRow.getMap());
    				if (outRow >= 0)
    					tuple.setString(outRow,row);
    				if (resultCountIndex >=0) {
    					tuple.setInt(resultCountIndex, numResults);
    				}
    				out.submit(tuple);
    			}
    			// commenting out for now, pending a discussion
    			/*
    				if (OutputMode.TUPLES == outputMode) {
    					out.punctuate(Punctuation.WINDOW_MARKER);
    				}
    			 */
    			currRow = results.next();
    		}	
    		// All done!
    		results.close();
    		if (logger.isInfoEnabled()) logger.info("Close result set.");
    		// See if there's any more work to do.
    		thisScan = regionQueue.poll();
    	} // end while
    	
    	// This function decides whether to send punctuation.  We send a window marker when all threads have finished. 
    	threadFinished();
    	if (logger.isInfoEnabled()) logger.info("Thread finishing");
    }

}