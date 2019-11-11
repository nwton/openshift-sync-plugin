/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.scm.SCM;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStrategy;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.JenkinsPipelineBuildStrategy;

import jenkins.branch.Branch;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BuildConfigToJobMapper {
	public static final String JENKINS_PIPELINE_BUILD_STRATEGY = "JenkinsPipeline";
	public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";
	private static final Logger LOGGER = Logger.getLogger(BuildConfigToJobMapper.class.getName());

	public static FlowDefinition mapBuildConfigToFlow(BuildConfig bc) throws IOException {
		if (!OpenShiftUtils.isPipelineStrategyBuildConfig(bc)) {
			return null;
		}

		BuildConfigSpec spec = bc.getSpec();
		BuildSource source = null;
		String jenkinsfile = null;
		String jenkinsfilePath = null;
    ObjectMeta buildConfigMetadata = bc.getMetadata();
    if (buildConfigMetadata != null){
      String buildConfigName = buildConfigMetadata.getName();
      LOGGER.info("Mapping BuildConfig " + buildConfigName + " to FlowDefinition");
    }
		if (spec != null) {
			source = spec.getSource();
			BuildStrategy strategy = spec.getStrategy();
			if (strategy != null) {
				JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
				if (jenkinsPipelineStrategy != null) {
					jenkinsfile = jenkinsPipelineStrategy.getJenkinsfile();
					jenkinsfilePath = jenkinsPipelineStrategy.getJenkinsfilePath();
				}
			}
		}
		if (jenkinsfile == null) {
			// Is this a Jenkinsfile from Git SCM?
			if (source != null && source.getGit() != null && source.getGit().getUri() != null) {
				if (jenkinsfilePath == null) {
					jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
				}
				if (!isEmpty(source.getContextDir())) {
					jenkinsfilePath = new File(source.getContextDir(), jenkinsfilePath).getPath();
				}
				GitSCM gitSCM = getGitSCM(bc);
				return new CpsScmFlowDefinition(gitSCM, jenkinsfilePath);
			} else {
				LOGGER.warning("BuildConfig does not contain source repository information - "
						+ "cannot map BuildConfig to Jenkins job");
				return null;
			}
		} else {
			return new CpsFlowDefinition(jenkinsfile, true);
		}
	}

	private static GitSCM getGitSCM(BuildConfig bc){
	  if (bc != null){
	    BuildSource source = bc.getSpec().getSource();
	    if (source != null){
        GitBuildSource gitSource = source.getGit();
        String branchRef = gitSource.getRef();
        String gitUrl = gitSource.getUri();
        List<BranchSpec> branchSpecs = Collections.emptyList();
        String credentialsId = null;
        LocalObjectReference sourceSecret = source.getSourceSecret();
        if (sourceSecret != null){
          try{
            credentialsId = updateSourceCredentials(bc);
          } catch (Exception e){
            LOGGER.warning("Encountered "+e+" during updateSourceCredentials()");
          }
        }
        if (isNotBlank(branchRef)) {
          branchSpecs = Collections.singletonList(new BranchSpec(branchRef));
        }
        UserRemoteConfig userRemoteConfig = new UserRemoteConfig(gitUrl, null, branchRef, credentialsId);
        // if credentialsID is null, go with an SCM where anonymous has to be sufficient
        List userRemoteList = Collections.singletonList(userRemoteConfig);
        List SubmoduleList = Collections.<GitSCMExtension>emptyList();
        List GitSCMExtensionList = Collections.<GitSCMExtension>emptyList();
        GitSCM gitSCM = new GitSCM(userRemoteList, branchSpecs, false, SubmoduleList, null, null, GitSCMExtensionList);

        return gitSCM;
      }
    }
    return null;
  }

	/**
	 * Updates the {@link BuildConfig} if the Jenkins {@link WorkflowJob} changes
   *
	 * @param job
	 *            the job thats been updated via Jenkins
	 * @param buildConfig
	 *            the OpenShift BuildConfig to update
	 * @return true if the BuildConfig was changed
   * This will be decided if the Definition in the Job is of type CpsFlowDefinition or CpsScmFlowDefinition
   *
	 */
	public static boolean updateBuildConfigFromJob(WorkflowJob job, BuildConfig buildConfig) {
    NamespaceName namespaceName = NamespaceName.create(buildConfig);
    JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = null;
    BuildConfigSpec spec = buildConfig.getSpec();
    if (spec != null) {
      BuildStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
      }
    }

    if (jenkinsPipelineStrategy == null) {
      LOGGER.warning("No jenkinsPipelineStrategy available in the BuildConfig " + namespaceName);
      return false;
    }

    LOGGER.info("Updating BuildConfig From Job " + namespaceName);

    FlowDefinition definition = job.getDefinition();
    String jenkinsfilePath = jenkinsPipelineStrategy.getJenkinsfilePath();
    if (definition instanceof CpsScmFlowDefinition) {
      BuildSource source = getOrCreateBuildSource(spec);
      CpsScmFlowDefinition cpsScmFlowDefinition = (CpsScmFlowDefinition) definition;
      String scriptPath = cpsScmFlowDefinition.getScriptPath();
      SCM scm = cpsScmFlowDefinition.getScm();
      if (scm instanceof GitSCM){
        GitSCM gitSCM = (GitSCM) scm;
        List<UserRemoteConfig> userRemoteConfigs = gitSCM.getUserRemoteConfigs();
        LocalObjectReference sourceSecret = source.getSourceSecret();
        if  (sourceSecret != null){
          String sourceSecretName = sourceSecret.getName();
          UserRemoteConfig sourceSecretUserRemoteConfig = new UserRemoteConfig(null, null,null, sourceSecretName);
          if (!userRemoteConfigs.contains(sourceSecretUserRemoteConfig)){
            LOGGER.info("Adding Build SourceSecret " + sourceSecretName + " as UserRemoteConfig");
            userRemoteConfigs.add(sourceSecretUserRemoteConfig);
          }
        }
      }
      if (scriptPath != null && scriptPath.trim().length() > 0) {
        boolean rc = false;
        String bcContextDir = source.getContextDir();
        if (StringUtils.isNotBlank(bcContextDir) && scriptPath.startsWith(bcContextDir)) {
          scriptPath = scriptPath.replaceFirst("^" + bcContextDir + "/?", "");
        }

        if (!scriptPath.equals(jenkinsfilePath)) {
          LOGGER.log(Level.FINE,
            "updating bc " + namespaceName + " jenkinsfile path to " + scriptPath + " from ");
          rc = true;
          jenkinsPipelineStrategy.setJenkinsfilePath(scriptPath);
        }

        scm = cpsScmFlowDefinition.getScm();
        if (scm instanceof GitSCM) {
          populateFromGitSCM(buildConfig, source, (GitSCM) scm, null);
          LOGGER.log(Level.FINE, "updating bc " + namespaceName);
          rc = true;
        }
        return rc;
      }
      return false;
    }

    if (definition instanceof CpsFlowDefinition) {
      CpsFlowDefinition cpsFlowDefinition = (CpsFlowDefinition) definition;
      String jenkinsFileFromDefinition = cpsFlowDefinition.getScript();
      String jenkinsFileFromStrategy = jenkinsPipelineStrategy.getJenkinsfile();
      if (jenkinsFileFromDefinition != null && jenkinsFileFromDefinition.trim().length() > 0
        && !jenkinsFileFromDefinition.equals(jenkinsFileFromStrategy)) {
        LOGGER.log(Level.FINE, "updating bc " + namespaceName + " jenkinsfile to " + jenkinsFileFromDefinition
          + " where old jenkinsfile was " + jenkinsFileFromStrategy);
        jenkinsPipelineStrategy.setJenkinsfile(jenkinsFileFromDefinition);
        return true;
      }

      return false;
    }

    // support multi-branch or github organization jobs
    BranchJobProperty property = job.getProperty(BranchJobProperty.class);
    if (property != null) {
      Branch branch = property.getBranch();
      if (branch != null) {
        String ref = branch.getName();
        SCM scm = branch.getScm();
        BuildSource source = getOrCreateBuildSource(spec);
        if (scm instanceof GitSCM) {
          if (populateFromGitSCM(buildConfig, source, (GitSCM) scm, ref)) {
            if (StringUtils.isEmpty(jenkinsfilePath)) {
              jenkinsPipelineStrategy.setJenkinsfilePath("Jenkinsfile");
            }
            return true;
          }
        }
      }
    }

    LOGGER.warning("Cannot update BuildConfig " + namespaceName + " as the definition is of class "
      + (definition == null ? "null" : definition.getClass().getName()));
    return false;
  }

	private static boolean populateFromGitSCM(BuildConfig buildConfig, BuildSource source, GitSCM gitSCM, String ref) {
		source.setType("Git");
		List<RemoteConfig> repositories = gitSCM.getRepositories();
		if (repositories != null && repositories.size() > 0) {
			RemoteConfig remoteConfig = repositories.get(0);
			List<URIish> urIs = remoteConfig.getURIs();
			if (urIs != null && urIs.size() > 0) {
				URIish urIish = urIs.get(0);
				String gitUrl = urIish.toString();
				if (gitUrl != null && gitUrl.length() > 0) {
					if (StringUtils.isEmpty(ref)) {
						List<BranchSpec> branches = gitSCM.getBranches();
						if (branches != null && branches.size() > 0) {
							BranchSpec branchSpec = branches.get(0);
							String branch = branchSpec.getName();
							while (branch.startsWith("*") || branch.startsWith("/")) {
								branch = branch.substring(1);
							}
							if (!branch.isEmpty()) {
								ref = branch;
							}
						}
					}
					OpenShiftUtils.updateGitSourceUrl(buildConfig, gitUrl, ref);
					return true;
				}
			}
		}
		return false;
	}

	private static BuildSource getOrCreateBuildSource(BuildConfigSpec spec) {
		BuildSource source = spec.getSource();
		if (source == null) {
			source = new BuildSource();
			spec.setSource(source);
		}
		return source;
	}
}
