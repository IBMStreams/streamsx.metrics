package com.ibm.streamsx.metrics;


import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.PrimitiveOperator;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDB.ConsistencyLevel;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;

/**
 * Class for an operator that consumes tuples. 
 * The following event methods from the Operator interface can be called:
 * </p>
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to process and submit tuples</li> 
 * <li>process() handles a tuple arriving on an input port 
 * <li>processPuncuation() handles a punctuation mark arriving on an input port 
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any time, 
 * such as a request to stop a PE or cancel a job. 
 * Thus the shutdown() may occur while the operator is processing tuples, punctuation marks, 
 * or even during port ready notification.</li>
 * </ul>
 * <p>With the exception of operator initialization, all the other events may occur concurrently with each other, 
 * which lead to these methods being called concurrently by different threads.</p> 
 */
@PrimitiveOperator(
		name="MetricsSink",
		namespace="com.ibm.streamsx.metrics",
		description=MetricsSource.DESC_OPERATOR
		)
@InputPorts({
	@InputPortSet(
			description="Port that ingests tuples",
			cardinality=1,
			optional=false,
			windowingMode=WindowMode.NonWindowed,
			windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious
			),
	@InputPortSet(
			description="Optional input ports",
			optional=true,
			windowingMode=WindowMode.NonWindowed,
			windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious
			)
})
@Libraries({
	"opt/downloaded/*"
	})
public class MetricsSink extends AbstractOperator {

	// ------------------------------------------------------------------------
	// Documentation.
	// Attention: To add a newline, use \\n instead of \n.
	// ------------------------------------------------------------------------

	static final String DESC_OPERATOR = 
			"The MetricsSink operator received metrics as tuples from the "
			+ "MetricsSource operator and outputs these metrics to "
			+ "ElasticSearch and, optionally, InfluxDB.\\n"
			+ "\\n"
			+ "By default, the MetricsSink does not output to InfluxDB. In "
			+ "order to enable this feature, set the optional parameter, "
			+ "outputToInfluxDB to true.\\n"
			+ "\\n"
			+ "The MetricsSink uses the default hostname and hostport that "
			+ "comes configured with ElasticSearch and InfluxDB. If you have "
			+ "custom settings, this can be configured by editing the optional "
			+ "hostname, hostport, username, and password parameters.\\n"
			+ "\\n"
			+ "Per default, the name of the database to output to is "
			+ "\"streamsdb\". This can be changed by editing to optional "
			+ "parameter, databaseName.\\n"
			+ "\\n"
			+ "Once the data is outputted to either ElasticSearch or InfluxDB, "
			+ "you can query the database and create custom graphs to display "
			+ "this data with graphing tools such as Grafana and Kibana.\\n"
			;

	private static final String DESC_OUTPUT_TO_INFLUXDB = 
			"Specifies whether to output metrics to InfluxDB.";

	private static final String DESC_INFLUXDB_USERNAME = 
			"Specifies the username of the InfluxDB server.";

	private static final String DESC_INFLUXDB_PASSWORD = 
			"Specifies the password of the InfluxDB server.";

	private static final String DESC_INFLUXDB_HOSTNAME = 
			"Specifies the hostname of the InfluxDB server.";

	private static final String DESC_INFLUXDB_HOSTPORT = 
			"Specifies the hostport of the InfluxDB server.";

	private static final String DESC_ELASTICSEARCH_HOSTNAME = 
			"Specifies the hostname of the ElasticSearch server.";

	private static final String DESC_ELASTICSEARCH_HOSTPORT = 
			"Specifies the hostport of the ElasticSearch server.";
	
	private static final String DESC_DATABASE_NAME = 
			"Specifies the name for the databases.";

	@Parameter(
			optional=true,
			description=MemoryMetricsSink.DESC_OUTPUT_TO_INFLUXDB
			)
	public void set_output_to_influxdb(Boolean output) throws IOException {
		outputToInfluxDB = output;
	}

	@Parameter(
			optional=true,
			description=MemoryMetricsSink.DESC_INFLUXDB_USERNAME
			)
	public void set_influxdb_username(String username) throws IOException {
		influxUsername = username;
	}

	@Parameter(
			optional=true,
			description=MemoryMetricsSink.DESC_INFLUXDB_PASSWORD
			)
	public void set_influxdb_password(String password) throws IOException {
		influxPassword = password;
	}

	@Parameter(
			optional=true,
			description=MemoryMetricsSink.DESC_INFLUXDB_HOSTNAME
			)
	public void set_influxdb_hostname(String hostname) throws IOException {
		influxHostName = hostname;
	}

	@Parameter(
			optional=true,
			description=MemoryMetricsSink.DESC_INFLUXDB_HOSTPORT
			)
	public void set_influxdb_hostport(int hostport) throws IOException {
		influxHostPort = String.valueOf(hostport);
	}
	
	@Parameter(
			optional=false,
			description=MemoryMetricsSink.DESC_ELASTICSEARCH_HOSTNAME
			)
	public void set_elasticsearch_hostname(String hostname) throws IOException {
		elasticSearchHostName = hostname;
	}

	@Parameter(
			optional=false,
			description=MemoryMetricsSink.DESC_ELASTICSEARCH_HOSTPORT
			)
	public void set_elasticsearch_hostport(int hostport) throws IOException {
		elasticSearchHostPort = hostport;
	}
	
	@Parameter(
			optional=true,
			description=MemoryMetricsSink.DESC_DATABASE_NAME
			)
	public void set_database_name(String name) throws IOException {
		databaseName = name;
	}

	
	// ------------------------------------------------------------------------
	// Implementation.
	// ------------------------------------------------------------------------
	
	/**
	 * InfluxDB configuration.
	 */
	private Boolean outputToInfluxDB = false;
	private InfluxDB influxDB = null;
	private String influxUsername = "admin";
	private String influxPassword = "admin";
	private String influxHostName = "http://localhost";
	private String influxHostPort = "8086";
	private BatchPoints batchPoints = null;

	/**
	 * ElasticSearch configuration.
	 */
	private TransportClient client = null;
	private XContentBuilder builder = null;
	private String elasticSearchHostName = "localhost";
	private int elasticSearchHostPort = 9300;

	/**
	 * Database name.
	 */
	private String databaseName = "streamsdb";
	
	/**
	 * Logger for tracing.
	 */
	private static Logger _trace = Logger.getLogger(MetricsSink.class.getName());
	
	/**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@SuppressWarnings("resource")
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
		super.initialize(context);
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        
        // Connect to InfluxDB server.
        if(outputToInfluxDB) {
            influxDB = InfluxDBFactory.connect(influxHostName + ":" + influxHostPort, influxUsername, influxPassword);
        	influxDB.createDatabase(databaseName);

	    	// Initialize object to contain batch of points.
	    	batchPoints = BatchPoints
		    	.database(databaseName)
	    		.tag("async", "true")
	            .retentionPolicy("autogen")
	            .consistency(ConsistencyLevel.ALL)
	            .build();
        }

    	// Connect to ElasticSearch server.
        Settings settings = Settings.builder().put("cluster.name","elasticsearch").build();
		client = new PreBuiltTransportClient(settings).addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(elasticSearchHostName), elasticSearchHostPort));
		
		// Create index if it doesn't exist.
		if(!client.admin().indices().prepareExists(databaseName).execute().actionGet().isExists()) {
			client.admin().indices().create(Requests.createIndexRequest(databaseName)).actionGet();
		}
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
    }

	/**
     * Process an incoming tuple that arrived on the specified port.
     * @param stream Port the tuple is arriving on.
     * @param tuple Object representing the incoming tuple.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple)
            throws Exception {

    	// Create InfluxDB point to output.
		if(outputToInfluxDB) {
    		// Create InfluxDB point to output.
			Point point = Point.measurement("metrics")
				.time(tuple.getLong("lastTimeRetrieved"), TimeUnit.MILLISECONDS)
				.addField("domainName", tuple.getString("domainName"))
				.addField("instanceName", tuple.getString("instanceName"))
				.addField("jobId", tuple.getString("jobId"))
				.addField("jobName", tuple.getString("jobName"))
				.addField("operatorName", tuple.getString("operatorName"))
				.addField("portIndex", tuple.getInt("portIndex"))
				.addField(tuple.getString("metricName"), tuple.getLong("metricValue"))
				.build();
        
			// Store in batch until window marker is received.
			batchPoints.point(point);
		}
        
        // Create ElasticSearch JSON to output.
        DateFormat df = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ss.SSSZZ");
        
        builder = XContentFactory.jsonBuilder()
        		.startObject()
	        		.field("lastTimeRetrieved", df.format(new Date((tuple.getLong("lastTimeRetrieved")))))
        			.field("domainName", tuple.getString("domainName"))
        			.field("instanceName", tuple.getString("instanceName"))
        			.field("jobId", tuple.getString("jobId"))
        			.field("jobName", tuple.getString("jobName"))
        			.field("operatorName", tuple.getString("operatorName"))
        			.field("portIndex", tuple.getString("portIndex"))
    				.field(tuple.getString("metricName"), 
    						Integer.parseInt(tuple.getString("metricValue")))
        		.endObject();
    }
    
	/**
     * Process an incoming punctuation that arrived on the specified port.
     * @param stream Port the punctuation is arriving on.
     * @param mark The punctuation mark
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void processPunctuation(StreamingInput<Tuple> stream,
    		Punctuation mark) throws Exception {

    	if(outputToInfluxDB) {
	    	// Output metrics to InfluxDB.
	    	if(batchPoints != null) {
	    		influxDB.write(batchPoints);
	    	}
		}

        // Output metrics to ElasticSearch.
        if(builder != null) {
	        @SuppressWarnings("unused")
			IndexResponse response = client.prepareIndex(databaseName, "metrics")
	            	.setSource(builder)
	            	.get();
        }
    }

	/**
     * Shutdown this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );

        // Close connection to ElasticSearch server.
        client.close();
        
        super.shutdown();
    }
    
}
