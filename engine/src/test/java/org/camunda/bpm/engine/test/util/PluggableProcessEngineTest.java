/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.test.util;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.AuthorizationService;
import org.camunda.bpm.engine.CaseService;
import org.camunda.bpm.engine.DecisionService;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.FilterService;
import org.camunda.bpm.engine.FormService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cmmn.entity.runtime.CaseSentryPartQueryImpl;
import org.camunda.bpm.engine.impl.cmmn.execution.CmmnExecution;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.interceptor.CommandExecutor;
import org.camunda.bpm.engine.impl.persistence.entity.JobEntity;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.CaseExecution;
import org.camunda.bpm.engine.runtime.CaseInstance;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.variable.VariableMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;


/** Base class for the process engine test cases.
 *
 * The main reason not to use our own test support classes is that we need to
 * run our test suite with various configurations, e.g. with and without spring,
 * standalone or on a server etc.  Those requirements create some complications
 * so we think it's best to use a separate base class.  That way it is much easier
 * for us to maintain our own codebase and at the same time provide stability
 * on the test support classes that we offer as part of our api (in org.camunda.bpm.engine.test).
 *
 * @author Tom Baeyens
 * @author Joram Barrez
 */
public class PluggableProcessEngineTest {

  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  protected ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(engineRule).around(testRule);

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  protected ProcessEngine processEngine;
  protected ProcessEngineConfigurationImpl processEngineConfiguration;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected FormService formService;
  protected HistoryService historyService;
  protected IdentityService identityService;
  protected ManagementService managementService;
  protected AuthorizationService authorizationService;
  protected CaseService caseService;
  protected FilterService filterService;
  protected ExternalTaskService externalTaskService;
  protected DecisionService decisionService;

  public PluggableProcessEngineTest() {
  }

  @Before
  public void initializeServices() {
    processEngine = engineRule.getProcessEngine();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    repositoryService = processEngine.getRepositoryService();
    runtimeService = processEngine.getRuntimeService();
    taskService = processEngine.getTaskService();
    formService = processEngine.getFormService();
    historyService = processEngine.getHistoryService();
    identityService = processEngine.getIdentityService();
    managementService = processEngine.getManagementService();
    authorizationService = processEngine.getAuthorizationService();
    caseService = processEngine.getCaseService();
    filterService = processEngine.getFilterService();
    externalTaskService = processEngine.getExternalTaskService();
    decisionService = processEngine.getDecisionService();
  }

  public ProcessEngine getProcessEngine() {
    return processEngine;
  }

  public boolean areJobsAvailable() {
    List<Job> list = managementService.createJobQuery().list();
    for (Job job : list) {
      if (!job.isSuspended() && job.getRetries() > 0 && (job.getDuedate() == null || ClockUtil.getCurrentTime().after(job.getDuedate()))) {
        return true;
      }
    }
    return false;
  }

  protected List<ActivityInstance> getInstancesForActivityId(ActivityInstance activityInstance, String activityId) {
    List<ActivityInstance> result = new ArrayList<>();
    if(activityInstance.getActivityId().equals(activityId)) {
      result.add(activityInstance);
    }
    for (ActivityInstance childInstance : activityInstance.getChildActivityInstances()) {
      result.addAll(getInstancesForActivityId(childInstance, activityId));
    }
    return result;
  }

  protected void deleteHistoryCleanupJobs() {
    final List<Job> jobs = historyService.findHistoryCleanupJobs();
    for (final Job job: jobs) {
      processEngineConfiguration.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
        commandContext.getJobManager().deleteJob((JobEntity) job);
        return null;
      });
    }
  }

  // CMMN METHODS

  // create case instance
  protected CaseInstance createCaseInstance() {
    return createCaseInstance(null);
  }

  protected CaseInstance createCaseInstance(String businessKey) {
    String caseDefinitionKey = repositoryService.
        createCaseDefinitionQuery()
        .singleResult()
        .getKey();

    return createCaseInstanceByKey(caseDefinitionKey, businessKey);
  }

  protected CaseInstance createCaseInstanceByKey(String caseDefinitionKey) {
    return createCaseInstanceByKey(caseDefinitionKey, null, null);
  }

  protected CaseInstance createCaseInstanceByKey(String caseDefinitionKey, String businessKey) {
    return createCaseInstanceByKey(caseDefinitionKey, businessKey, null);
  }

  protected CaseInstance createCaseInstanceByKey(String caseDefinitionKey, VariableMap variables) {
    return createCaseInstanceByKey(caseDefinitionKey, null, variables);
  }

  protected CaseInstance createCaseInstanceByKey(String caseDefinitionKey, String businessKey, VariableMap variables) {
    return caseService
        .withCaseDefinitionByKey(caseDefinitionKey)
        .businessKey(businessKey)
        .setVariables(variables)
        .create();
  }

  // queries

  protected CaseExecution queryCaseExecutionByActivityId(String activityId) {
    return caseService
        .createCaseExecutionQuery()
        .activityId(activityId)
        .singleResult();
  }

  protected CaseExecution queryCaseExecutionById(String caseExecutionId) {
    return caseService
        .createCaseExecutionQuery()
        .caseExecutionId(caseExecutionId)
        .singleResult();
  }

  protected CaseSentryPartQueryImpl createCaseSentryPartQuery() {
    CommandExecutor commandExecutor = processEngineConfiguration.getCommandExecutorTxRequiresNew();
    return new CaseSentryPartQueryImpl(commandExecutor);
  }

  // transition methods

  protected void close(final String caseExecutionId) {
    caseService
        .withCaseExecution(caseExecutionId)
        .close();
  }

  protected void complete(final String caseExecutionId) {
    caseService
        .withCaseExecution(caseExecutionId)
        .complete();
  }

  protected CaseInstance create(final String caseDefinitionId) {
    return caseService
        .withCaseDefinition(caseDefinitionId)
        .create();
  }

  protected CaseInstance create(final String caseDefinitionId, final String businessKey) {
    return caseService
        .withCaseDefinition(caseDefinitionId)
        .businessKey(businessKey)
        .create();
  }

  protected void disable(final String caseExecutionId) {
    caseService
        .withCaseExecution(caseExecutionId)
        .disable();
  }

  protected void exit(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).exit();
      }
    });
  }

  protected void manualStart(final String caseExecutionId) {
    caseService
        .withCaseExecution(caseExecutionId)
        .manualStart();
  }

  protected void occur(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).occur();
      }
    });
  }

  protected void parentResume(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).parentResume();
      }
    });
  }

  protected void parentSuspend(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).parentSuspend();

      }
    });
  }

  protected void parentTerminate(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).parentTerminate();
      }
    });
  }

  protected void reactivate(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).reactivate();
      }
    });
  }

  protected void reenable(final String caseExecutionId) {
    caseService
        .withCaseExecution(caseExecutionId)
        .reenable();
  }

  protected void resume(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).resume();
      }
    });
  }

  protected void suspend(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).suspend();
      }
    });
  }

  protected void terminate(final String caseExecutionId) {
    executeHelperCaseCommand(new HelperCaseCommand() {
      public void execute() {
        getExecution(caseExecutionId).terminate();
      }
    });
  }

  protected void executeHelperCaseCommand(HelperCaseCommand command) {
    processEngineConfiguration
        .getCommandExecutorTxRequired()
        .execute(command);
  }

  protected abstract class HelperCaseCommand implements Command<Void> {

    protected CmmnExecution getExecution(String caseExecutionId) {
      return (CmmnExecution) caseService
          .createCaseExecutionQuery()
          .caseExecutionId(caseExecutionId)
          .singleResult();
    }

    public Void execute(CommandContext commandContext) {
      execute();
      return null;
    }

    public abstract void execute();

  }
}
