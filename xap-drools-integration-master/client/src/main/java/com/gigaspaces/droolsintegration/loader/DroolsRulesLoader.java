package com.gigaspaces.droolsintegration.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutionException;

import org.apache.log4j.Logger;
import org.openspaces.core.GigaSpace;
import org.openspaces.core.context.GigaSpaceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.c123.demo.drools.client.DemoClient;
import com.gigaspaces.async.AsyncFuture;
import com.gigaspaces.droolsintegration.util.RulesConstants;
import com.gigaspaces.droolsintegration.model.drools.DroolsDslDefinition;
import com.gigaspaces.droolsintegration.model.drools.DroolsRuleAddEvent;
import com.gigaspaces.droolsintegration.model.drools.DroolsRuleRemoveEvent;
import com.gigaspaces.droolsintegration.util.xml.XmlAdd;
import com.gigaspaces.droolsintegration.util.xml.XmlChangeSet;
import com.gigaspaces.droolsintegration.util.xml.XmlReader;
import com.gigaspaces.droolsintegration.util.xml.XmlRemove;
import com.gigaspaces.droolsintegration.util.xml.XmlResource;


@Component
public class DroolsRulesLoader {

    private static Logger log = Logger.getLogger(DroolsRulesLoader.class);   

    @GigaSpaceContext(name = "gigaSpace")
	private GigaSpace gigaSpace;
    
    @Autowired
    @SuppressWarnings("rawtypes")
    private LinkedList ruleDefinitions;

    @Autowired
    private HashMap<String, String> ruleSets;

    @Autowired
    private XmlReader xmlReader;
    
    private int numberOfPartitions=0;
    
    public static void main(String[] args) {   
    	System.out.println("Start");
    	ClassPathXmlApplicationContext applicationContext = null;
    	try {    		
    		applicationContext = new ClassPathXmlApplicationContext("applicationContext.xml");
	    	
	    	DroolsRulesLoader droolsRulesLoader = (DroolsRulesLoader) applicationContext.getBean("droolsRulesLoader");
	    	
	    	droolsRulesLoader.execute();
    	}catch(Exception e) {
    		log.error(e);
    	}finally {
    		if(applicationContext != null) {
    			applicationContext.close(); applicationContext = null;
    		}
    	}
    	System.out.println("End");
	}
    
    public void execute() throws IOException, InterruptedException, ExecutionException {  
    	
    	AsyncFuture<Integer> future = gigaSpace.execute(new NumberOfParitionsTask());
	    numberOfPartitions = future.get(); 
	    log.info("========= execute() number of partitions are: " + numberOfPartitions + " ==========");
		
    	
    	
    	Collection<DroolsDslDefinition> definitions = new ArrayList<DroolsDslDefinition>();

        for(Object ruleDefinition : ruleDefinitions) {
            DroolsDslDefinition definition = new DroolsDslDefinition();
            
            definition.setRuleSet(DemoClient.RULE_SET_VOUCHER);
            definition.setDslBytes(loadDefinition((String) ruleDefinition));
            definitions.add(definition);
        }
        
        if(ruleSets != null && !ruleSets.isEmpty()) {
	        ArrayList<DroolsRuleAddEvent> droolsRuleAddEventList = new ArrayList<DroolsRuleAddEvent>();
	        ArrayList<DroolsRuleRemoveEvent> droolsRuleRemoveEventList = new ArrayList<DroolsRuleRemoveEvent>();
	       
	        for(Map.Entry<String, String> ruleSetMapEntry : ruleSets.entrySet()) {
	        	
	            String ruleSourcePath = ruleSetMapEntry.getValue();
	            log.info("========= processing ruleSet: " + ruleSetMapEntry.getKey() + " with ruleSourcePath: " + ruleSourcePath + " ==========");
	
	            if(ruleSourcePath.endsWith(RulesConstants.EXTENSION_XML)) { //DRL/DSLR package
	                
	                XmlChangeSet changeSet = xmlReader.readDroolsRuleResourceFromXml(ruleSourcePath);
	                if(changeSet != null) {
	                	XmlAdd xmlAdd = changeSet.getAdd();
	                    if(xmlAdd != null) {
	                    	addRule(xmlAdd, droolsRuleAddEventList, ruleSetMapEntry);
	                    }
	                    
	                    XmlRemove xmlRemove = changeSet.getRemove();
	                    if(xmlRemove != null) {
	                    	removeRule(xmlRemove, droolsRuleRemoveEventList, ruleSetMapEntry);
	                    }
	                    
	                    
	                }else {
	                    log.error("Error reading XML file (" + ruleSetMapEntry.getValue() + "), skipping...");
	                }
	            }else {
	                log.error("Unknown file type (" + ruleSetMapEntry.getValue() + "), skipping...");
	            }
	            log.info("========= ruleSourcePath (" + ruleSourcePath + ") loaded ==========");
	        }
	        
	        if(droolsRuleAddEventList.size() > 0) {
	        	gigaSpace.writeMultiple(droolsRuleAddEventList.toArray());
	        }
	        
	        if(droolsRuleRemoveEventList.size() > 0) {
	        	gigaSpace.writeMultiple(droolsRuleRemoveEventList.toArray());
	        }
        }
    }
    
    private void addRule(XmlAdd xmlAdd, ArrayList<DroolsRuleAddEvent> droolsRuleAddEventList, Map.Entry<String, String> ruleSetMapEntry) throws IOException {
    	for(XmlResource resource : xmlAdd.getResources()) {
        	
            String source = resource.getSource();
            String type = RulesConstants.RESOURCE_TYPE_DRL;
            
            if(!(RulesConstants.RESOURCE_TYPE_DRL.equals(type) || RulesConstants.RESOURCE_TYPE_DSLR.equals(type))) {
                log.error("Unknown resource type (" + ruleSetMapEntry.getValue() + "), skipping...");
                continue;
            }
            for (int partitionCounter=1; partitionCounter<=numberOfPartitions; partitionCounter++) {
	            DroolsRuleAddEvent droolsRuleAddEvent = new DroolsRuleAddEvent();
	            
	            droolsRuleAddEvent.setRuleSet(ruleSetMapEntry.getKey());
	            droolsRuleAddEvent.setOriginalResourceType(type);
	            droolsRuleAddEvent.setProcessed(false);
	            
	            String ruleStringFormat = loadRule(source.substring(10, source.length()));
	            droolsRuleAddEvent.setPackageName(parseKnowledgePackageName(ruleStringFormat));
	            droolsRuleAddEvent.setRuleName(parseRuleName(ruleStringFormat));
	            droolsRuleAddEvent.setRuleBytes(ruleStringFormat.getBytes());
	            
	            droolsRuleAddEvent.setRouting(partitionCounter);
	            
	            droolsRuleAddEventList.add(droolsRuleAddEvent);
            }
        }
    }
    
    private void removeRule(XmlRemove xmlRemove, ArrayList<DroolsRuleRemoveEvent> droolsRuleEventList, Map.Entry<String, String> ruleSetMapEntry) throws IOException {
    	for(XmlResource resource : xmlRemove.getResources()) {
        	
            String source = resource.getSource();
            String type = RulesConstants.RESOURCE_TYPE_DRL;
            
            if(!(RulesConstants.RESOURCE_TYPE_DRL.equals(type) || RulesConstants.RESOURCE_TYPE_DSLR.equals(type))) {
                log.error("Unknown resource type (" + ruleSetMapEntry.getValue() + "), skipping...");
                continue;
            }
            
            for (int partitionCounter=1; partitionCounter<=numberOfPartitions; partitionCounter++) {
	            DroolsRuleRemoveEvent droolsRuleRemoveEvent = new DroolsRuleRemoveEvent();
	            
	            droolsRuleRemoveEvent.setRuleSet(ruleSetMapEntry.getKey());
	            droolsRuleRemoveEvent.setProcessed(false);
	            
	            String ruleStringFormat = loadRule(source.substring(10, source.length()));
	            droolsRuleRemoveEvent.setPackageName(parseKnowledgePackageName(ruleStringFormat));
	            droolsRuleRemoveEvent.setRuleName(parseRuleName(ruleStringFormat));
	            droolsRuleRemoveEvent.setRouting(partitionCounter);
	            
	            droolsRuleEventList.add(droolsRuleRemoveEvent);
            }
            
        }
    }

    private byte[] loadDefinition(String source) throws IOException {
        InputStream is;
        InputStreamReader reader = null;

        try {
            log.info("Loading '" + source + "'");
            is = new ClassPathResource(source).getInputStream();
            reader = new InputStreamReader(is);

            StringWriter sw = new StringWriter();
            int i;
            while((i = reader.read()) != -1) {
                sw.write(i);
            }

            return sw.toString().getBytes();
        }finally {
            if(reader != null) {
                reader.close(); reader = null;
            }
        }
    }

    private String loadRule(String source) throws IOException {
        InputStream is;
        InputStreamReader reader = null;

        try {
            log.info("Loading '" + source + "'");
            is = new ClassPathResource(source).getInputStream();
            reader = new InputStreamReader(is);

            StringWriter sw = new StringWriter();
            int i;
            while((i = reader.read()) != -1) {
                sw.write(i);
            }

            return sw.toString();
        }finally {
            if(reader != null) {
                reader.close(); reader = null;
            }
        }
    }
    
    private String parseRuleName(String source) {
    	String begin = "rule ";
    	String end = "when";
    	int beginIndex = source.indexOf(begin);
    	int endIndex = source.indexOf(end);
    	
    	String ruleName = source.substring(beginIndex, endIndex);
    	StringTokenizer tokens = new StringTokenizer(ruleName);
    	tokens.nextToken();
    	String name = tokens.nextToken();
    	// remove the Apostrophes (") from rule name
    	String[] nameArray = name.split("\"");
    	name = nameArray[1];
    	log.info("Rule name " + name );
    	return name;
    }
    
    private String parseKnowledgePackageName(String source) {
    	String begin = "package ";
    	String end = "import";
    	
    	int beginIndex = source.indexOf(begin);
    	int endIndex = source.indexOf(end);
    	String packageName = source.substring(beginIndex, endIndex);
    	StringTokenizer tokens = new StringTokenizer(packageName);
    	tokens.nextToken();
    	String name = tokens.nextToken();
    	log.info("KnowledgePackageName " + name );
    	return name;
    }

}