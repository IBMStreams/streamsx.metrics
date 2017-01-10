//
// ****************************************************************************
// * Copyright (C) 2016, International Business Machines Corporation          *
// * All rights reserved.                                                     *
// ****************************************************************************
//

package com.ibm.streamsx.metrics.internal.filter;

import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;

import com.ibm.json.java.JSONArtifact;
import com.ibm.json.java.JSONObject;

public class JobParser extends AbstractParser {
	
	private static Logger _logger = Logger.getLogger(JobParser.class.getName());

	private static final String JOB_NAME_PATTERNS = "jobNamePatterns";
	
	private static final String OPERATORS = "operators";

	private static final String PES = "pes";

	private OperatorParser _operatorParser = new OperatorParser();
	
	private PeParser _peParser = new PeParser();
	
	protected JobParser() {

		setMandatoryItem(JOB_NAME_PATTERNS);

		setValidationRule(JOB_NAME_PATTERNS, new IValidator() {

			@Override
			public boolean validate(String key, Object object) {
				return verifyPatterns(key, object);
			}
			
		});

		setValidationRule(OPERATORS, new IValidator() {

			@Override
			public boolean validate(String key, Object object) {
				boolean result = true;
				if (object instanceof JSONArtifact) {
					result = _operatorParser.validate((JSONArtifact)object);
				}
				else {
					result = false;
					logger().error("filterDocument: The parsed object must be a JSONArtifact. Details: key=" + key + ", object=" + object);
				}
				return result;
			}
			
		});

		setValidationRule(PES, new IValidator() {

			@Override
			public boolean validate(String key, Object object) {
				boolean result = true;
				if (object instanceof JSONArtifact) {
//					result = _peParser.validate((JSONArtifact)object);
				}
				else {
					result = false;
					logger().error("filterDocument: The parsed object must be a JSONArtifact. Details: key=" + key + ", object=" + object);
				}
				return result;
			}
			
		});
	}

	@Override
	protected Logger logger() {
		return _logger;
	}

	@Override
	protected Set<Filter> buildFilters(JSONObject json) {
//		logger().error("Job.JSON=" + json);
		Set<String> patterns = buildPatternList(json.get(JOB_NAME_PATTERNS));
		Set<Filter> operatorFilters = _operatorParser.buildFilters((JSONArtifact)json.get(OPERATORS));
		Set<Filter> peFilters = _peParser.buildFilters((JSONArtifact)json.get(PES));
		Set<Filter> result = new HashSet<>();
		for (String pattern : patterns) {
//			logger().error("create job filter, pattern=" + pattern);
			result.add(new JobFilter(pattern, operatorFilters, peFilters));
		}
		return result;
	}

}
