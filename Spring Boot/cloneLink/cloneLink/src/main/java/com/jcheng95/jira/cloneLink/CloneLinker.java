package com.jcheng95.jira.cloneLink;

// Shrink imports from wildcards to be cut down on unnecessary imports

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import com.atlassian.jira.plugin.webfragment.model.JiraHelper;
import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.issuetype.*;
import com.atlassian.jira.user.*;
import com.atlassian.jira.component.*;
import com.atlassian.jira.issue.fields.*;
import com.atlassian.jira.issue.managers.*;
import com.atlassian.jira.jql.builder.*;
import com.atlassian.query.*;
import com.atlassian.jira.bc.issue.search.*;
import com.atlassian.jira.issue.search.*;
import com.atlassian.jira.web.bean.*;
import com.atlassian.jira.exception.*;

/* import com.atlassian.jira.jql.builder.JqlClauseBuilder;
import com.atlassian.jira.jql.builder.JqlQueryBuilder; */

/*
  
  Purpose:
  
  Create a custom epic cloning action that clones the epic and all task/stories/sub-tasks
  within and provides the relative contextual linking (i.e., linking to the cloned task rather than the original).
  Provides a recursive and iterative solution in case memory gets overloaded.
  
  Author:  				Jacky Cheng
  Date Created:    		Oct. 5, 2017
  Date Last Modified: 	Feb. 2, 2018
  
 */

@RestController
public class CloneLinker
{
	// This variable is created by Jira by default but causes a compile error for a package not existing
    // private static final jdk.internal.instrumentation.Logger log = LoggerFactory.getLogger(CloneLinker.class);
	protected Logger log;

    private ApplicationUser appUser;			// Currently logged-in user
    private Issue epicIssue;					// Issue currently being looked at by the logged-in user (ideally an epic)
    private JiraHelper issueHelper;				// Issue helper used to get epicIssue
    private IssueFactory issueCloner;			// Abstract class methods can't be accessed without an instance
    private CustomFieldManager cfm;				// Abstract class methods can't be accessed without an instance
    private IssueManager im;					// Abstract class methods can't be accessed without an instance
    private long epicLink;						// Custom field ID
    private long parentLink;					// Custom field ID
    private HashMap<Long, Long> origToCloneMap;	// Mapping the original issue to its cloned issue - key is original, value is clone
    // private HashMap<long, long> cloneToOrigMap;	// Mapping the cloned issue to its original issue - key is clone, value is original
	
	// Default constructor
    public CloneLinker()
    {
    	appUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
    	issueHelper = new JiraHelper();
    	epicIssue = (Issue) issueHelper.getContextParams().get("issue");
    	cfm = ComponentAccessor.getCustomFieldManager();
    	im = ComponentAccessor.getIssueManager();
    	epicLink = 10009; // ID for the issues related to a particular epic
    	parentLink = 11300; // ID for the issues only directly linked to the parent issue
    }
    
    /*
     * 
     * Parameters: 	Issue
     * Return: 		MutableIssue
     * 
     * Description: Copy all issue fields from the original issue to a mutable issue that will be cloned.
     * 
     */
    public MutableIssue copyAllFields(Issue orig)
    {
    	try {
    		log.debug("Copying fields to clone issue");
    		
    		if(orig == null) {
    			return null;
    		}
    		
    		// Clones the default fields
	    	MutableIssue clone = issueCloner.cloneIssueWithAllFields(orig);
	    	// Clones the custom fields
			List<CustomField> allFields = ComponentAccessor.getCustomFieldManager().getCustomFieldObjects(orig);
			
			for(CustomField field : allFields) {
				Object cloneCustomFieldValue;
				if(field.getIdAsLong() == parentLink) {
					long result = orig.getCustomFieldValue(field.getIdAsLong());
					cloneCustomFieldValue = (origToCloneMap.containsKey(result) != null) ? origToCloneMap.get(result) : result;
				}
				clone.setCustomFieldValue(field.getIdAsLong(),cloneCustomFieldValue);
			}
			
			return clone;
    	}
    	catch(IssueFieldsCharacterLimitExceededException ifclee) {
    		log.error("Field character limit exceeded", ifclee);
    		return null; // Needs to be handled in the case of a null pointer exception
    	}
    	catch(Exception e) {
    		log.error("Unexpected error", e);
    		e.printStackTrace();
    		return null; // Needs to be handled in the case of a null pointer exception
    	}
    }

    /*
     * 
     * Parameters: 	Issue
     * Return: 		None
     * 
     * Description: Clone the issue that is created from the cloned issue with a copied fields.
     * 
     * Notes:		An issue size can range from 11 KB to 1700 KB.
     * 
     */
    public void performClone(Issue orig)
    {
    	try {
    		log.debug("Calling the field copier and uploading the cloned issue");
			MutableIssue clone = copyAllFields(orig);
			if(clone != null) {
				ApplicationUser reporter = orig.getReporter();
				Issue clonedObject = ComponentAccessor.getIssueManager().createIssueObject(reporter, clone); // Does this actually create the clone?
				
				origToCloneMap[orig.getId()] = clonedObject.getId();
			}
    	}
    	catch(CreateException ce) {
    		log.error("Can't create the issue", ce);
    		return;
    	}
    	catch(Exception e) {
    		log.error("Unexpected error", e);
    		e.printStackTrace();
    		return;
    	}
    }

    /*
     * 
     * Parameters: 	Issue
     * Return: 		SearchResults
     * 
     * Description: Queries for all children issues related to the paramaterized issue.
     * 
     */
    public SearchResults performQuery(Issue i)
    {
    	try {
    		log.debug("Performing query on the issue provided as input");
	    	Query getChildren = JqlQueryBuilder.newBuilder().where().project(i.getProjectObject().getKey()).and().customField(parentLink).eq(i.getId()).buildQuery();
	    	SearchResults result = ComponentAccessor.getComponentOfType(SearchService.class).search(appUser, getChildren, PagerFilter.getUnlimitedFilter());
	    	
	    	return result;
    	}
    	catch(SearchException se) {
    		// Captures the error but will just return an empty list that should be input sensitized wherever this method is called
    		log.error("Error running search", se);
    		return null; // Needs to be handled in the case of a null pointer exception
    	}
    	catch(Exception e) {
    		log.error("Unexpected error", e);
    		e.printStackTrace();
    		return null; // Needs to be handled in the case of a null pointer exception
    	}
    }
    
    /*
     * 
     * Parameters: 	Issue
     * Return: 		None
     * 
     * Description: Recursive implementation for cloning all children issues. Be aware of space complexity especially on
     * 				Jira Cloud as this could potentially run up the memory of the RAM and bog down other users.
     * 
     * Notes:		An issue size can range from 11 KB to 1700 KB.
     * 
     */
    public void cloneChildrenRecursive(Issue current)
    {
    	try {
    		log.debug("Entering recursive solution.");
    		SearchResults res = performQuery(current);
    		
    		if(res != null) {
    			// Run a preorder-style traversal
    			performClone(current);
    			
    			List<Issue> childrenTasks = res.getIssues();
    			
        		if(!childrenTasks.isEmpty()) {
        			for(Issue iss : childrenTasks) {
        				cloneChildrenRecursive(iss);
        			}
        		}
    		}
    	}
    	catch(Exception e) {
    		log.error("Unexpected error", e);
    		e.printStackTrace();
    		return;
    	}
    }
    /*
     * 
     * Parameters: 	Issue
     * Return: 		None
     * 
     * Description: Iterative implementation for cloning all children issues. Be aware of space complexity especially on
     * 				Jira Cloud as this could potentially run up the memory of the RAM and bog down other users.
     * 
     * Notes:		An issue size can range from 11 KB to 1700 KB.
     * 
     */
    public void cloneChildrenIterative(Issue current)
    {
    	try {
    		log.debug("Entering iterative solution.");
    		
    		SearchResults res = performQuery(current);
    		if(res != null) {
    			
    			List<Issue> childrenTasks = res.getIssues();
    			// Using a hashmap to reduce access time
    			// Ignores access to the whole Issue object and looks for the ID
    			// As the data structure is designed, access should typically be O(1)
    			HashMap<Long, Boolean> visited = new HashMap<Long, Boolean>();
        		
    			// Clones the epic (or the root task)
    			performClone(current);
    			
        		while(!childrenTasks.isEmpty()) {
        			// Obtains all children, one at a time, from the query results
        			// Treats each result from the query as the root node of their subtree
        			Stack<Issue> childrenStack = new Stack<Issue>();
        			childrenStack.push(childrenTasks.remove(0));
        			
        			while(!childrenStack.empty()) {
        				Issue cur = childrenStack.pop();
        				
        				if(visited.containsKey(cur.getId()) == null) {
        					visited.put(cur.getId(), true);
        					performClone(cur);
        					
        					SearchResults subRes = performQuery(cur);
        					List<Issue> subChildren = subRes.getIssues();
        					
        					for(Issue iss : subChildren) {
        						childrenStack.push(iss);
        					}
        				}
        			}
        		}
    		}
    	}
    	// Catching all possible exceptions <- mainly to remove compile errors (not warnings)
    	catch(Exception e) {
    		log.error("Unexpected error", e);
    		e.printStackTrace();
    		return;
    	}
    }
    
    /*
     * 
     * Parameters: 	Issue
     * Return: 		None
     * 
     * Description: This is run when the action's .JSPA URL is invoked.
     * 
     * TODO: 		Provide functionality for observing node count (total and per layer) and depth count to determine
     * 				use of recursive vs. iterative for minimum memory usage.
     * 
     */
    @RequestMapping("/index")
    // ^ is this right?
    public String runClone() throws Exception {
    	// Using the parent class's logger variable
    	log.debug("Entering the execute method");
		
		// Runs a clone from the epic downward
		cloneChildrenRecursive(this.epicIssue);
		// cloneChildrenIterative(this.epicIssue);
    	
        return super.execute(); //returns SUCCESS
    }
}
