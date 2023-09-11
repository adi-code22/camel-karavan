/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.service;

import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.apache.camel.karavan.code.CodeService;
import org.apache.camel.karavan.code.DockerComposeConverter;
import org.apache.camel.karavan.docker.DockerForKaravan;
import org.apache.camel.karavan.code.model.DockerComposeService;
import org.apache.camel.karavan.git.GitService;
import org.apache.camel.karavan.git.model.GitConfig;
import org.apache.camel.karavan.git.model.GitRepo;
import org.apache.camel.karavan.infinispan.InfinispanService;
import org.apache.camel.karavan.infinispan.model.*;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.apache.camel.karavan.code.CodeService.DEV_SERVICES_FILENAME;
import static org.apache.camel.karavan.code.CodeService.PROJECT_COMPOSE_FILENAME;

@Default
@Readiness
@ApplicationScoped
public class ProjectService implements HealthCheck {

    private static final Logger LOGGER = Logger.getLogger(ProjectService.class.getName());

    @ConfigProperty(name = "karavan.environment")
    String environment;

    @ConfigProperty(name = "karavan.maven.cache")
    Optional<String> mavenCache;

    @Inject
    InfinispanService infinispanService;

    @Inject
    KubernetesService kubernetesService;

    @Inject
    DockerForKaravan dockerForKaravan;

    @Inject
    RegistryService registryService;

    @Inject
    GitService gitService;

    @Inject
    CodeService codeService;

    @Inject
    EventBus eventBus;

    private AtomicBoolean ready = new AtomicBoolean(false);

    @Override
    public HealthCheckResponse call() {
        if (ready.get()) {
            return HealthCheckResponse.named("Projects").up().build();
        } else {
            return HealthCheckResponse.named("Projects").down().build();
        }
    }

    public String runProjectWithJBangOptions(Project project, String jBangOptions) throws Exception {
        String containerName = project.getProjectId();
        ContainerStatus status = infinispanService.getDevModeContainerStatus(project.getProjectId(), environment);
        if (status == null) {
            status = ContainerStatus.createDevMode(project.getProjectId(), environment);
        }

        if (!Objects.equals(status.getState(), ContainerStatus.State.running.name())) {
            status.setInTransit(true);
            eventBus.send(ContainerStatusService.CONTAINER_STATUS, JsonObject.mapFrom(status));

            if (ConfigService.inKubernetes()) {
                kubernetesService.runDevModeContainer(project, jBangOptions);
            } else {
                Map<String, String> files = infinispanService.getProjectFiles(project.getProjectId()).stream()
                        .filter(f -> !Objects.equals(f.getName(), PROJECT_COMPOSE_FILENAME))
                        .collect(Collectors.toMap(ProjectFile::getName, ProjectFile::getCode));

                ProjectFile compose = infinispanService.getProjectFile(project.getProjectId(), PROJECT_COMPOSE_FILENAME);
                DockerComposeService dcs = DockerComposeConverter.fromCode(compose.getCode(), project.getProjectId());
                Map<String, String> volumes = mavenCache
                        .map(s -> Map.of(s, "/root/.m2"))
                        .orElseGet(Map::of);
                dockerForKaravan.runProjectInDevMode(project.getProjectId(), jBangOptions, dcs.getPortsMap(), files, volumes);
            }
            return containerName;
        } else {
            return null;
        }
    }

    public void buildProject(Project project, String tag) throws Exception {
        String templateName = project.getRuntime() + "-builder-script-docker.sh";
        String script = codeService.getTemplateText(templateName);

        tag = tag != null && !tag.isEmpty() && !tag.isBlank()
                ? tag
                : Instant.now().toString().substring(0, 19).replace(":", "-");

        List<String> env = getEnvForBuild(project, tag);
        if (ConfigService.inKubernetes()) {
            kubernetesService.runBuildProject(project, script, env, tag);
        } else {
            Map<String, String> volumes = mavenCache.map(s -> Map.of(s, "/root/.m2")).orElseGet(Map::of);
            dockerForKaravan.runBuildProject(project, script, env, volumes, tag);
        }
    }

    private List<String> getEnvForBuild(Project project, String tag) {
        GitConfig gitConfig = gitService.getGitConfigForBuilder();
        List<String> env = new ArrayList<>();
        env.addAll(registryService.getEnvForBuild());
        env.addAll(List.of(
                "GIT_REPOSITORY=" + gitConfig.getUri(),
                "GIT_USERNAME=" + gitConfig.getUsername(),
                "GIT_PASSWORD=" + gitConfig.getPassword(),
                "GIT_BRANCH=" + gitConfig.getBranch(),
                "PROJECT_ID=" + project.getProjectId(),
                "JBANG_REPO=~/.m2",
                "TAG=" + tag
        ));
        return env;
    }

    public List<Project> getAllProjects(String type) {
        if (infinispanService.isReady()) {
            List<ProjectFile> files = infinispanService.getProjectFilesByName(PROJECT_COMPOSE_FILENAME);
            return infinispanService.getProjects().stream()
                    .filter(p -> type == null || Objects.equals(p.getType().name(), type))
                    .sorted(Comparator.comparing(Project::getProjectId))
                    .collect(Collectors.toList());
        } else {
            return List.of();
        }
    }

    private String getImage(List<ProjectFile> files, String projectId) {
        Optional<ProjectFile> file = files.stream().filter(f -> Objects.equals(f.getProjectId(), projectId)).findFirst();
        if (file.isPresent()) {
            DockerComposeService service = DockerComposeConverter.fromCode(file.get().getCode(), projectId);
            String image = service.getImage();
            return Objects.equals(image, projectId) ? null : image;
        } else {
            return null;
        }
    }

    public Project save(Project project) throws Exception {
        boolean isNew = infinispanService.getProject(project.getProjectId()) == null;
        infinispanService.saveProject(project);
        if (isNew) {
            ProjectFile appProp = codeService.getApplicationProperties(project);
            infinispanService.saveProjectFile(appProp);
            if (!ConfigService.inKubernetes()) {
                ProjectFile projectCompose = codeService.createInitialProjectCompose(project);
                infinispanService.saveProjectFile(projectCompose);
            }
        }
        return project;
    }

    public Integer getProjectPort(String projectId) {
        ProjectFile composeFile = infinispanService.getProjectFile(projectId, PROJECT_COMPOSE_FILENAME);
        return codeService.getProjectPort(composeFile);
    }

    //    @Retry(maxRetries = 100, delay = 2000)
    public void tryStart() throws Exception {
        if (infinispanService.isReady() && gitService.checkGit()) {
            if (infinispanService.getProjects().isEmpty()) {
                importAllProjects();
            }
            addKameletsProject();
            addTemplatesProject();
            addServicesProject();
            ready.set(true);
        } else {
            LOGGER.info("Projects are not ready");
            throw new Exception("Projects are not ready");
        }
    }

    private void importAllProjects() {
        LOGGER.info("Import projects from Git");
        try {
            List<GitRepo> repos = gitService.readProjectsToImport();
            repos.forEach(repo -> {
                Project project;
                String folderName = repo.getName();
                if (folderName.equals(Project.Type.templates.name())) {
                    project = new Project(Project.Type.templates.name(), "Templates", "Templates", "", repo.getCommitId(), repo.getLastCommitTimestamp(), Project.Type.templates);
                } else if (folderName.equals(Project.Type.kamelets.name())) {
                    project = new Project(Project.Type.kamelets.name(), "Custom Kamelets", "Custom Kamelets", "", repo.getCommitId(), repo.getLastCommitTimestamp(), Project.Type.kamelets);
                } else if (folderName.equals(Project.Type.services.name())) {
                    project = new Project(Project.Type.services.name(), "Services", "Development Services", "", repo.getCommitId(), repo.getLastCommitTimestamp(), Project.Type.services);
                } else {
                    project = getProjectFromRepo(repo);
                }
                infinispanService.saveProject(project);

                repo.getFiles().forEach(repoFile -> {
                    ProjectFile file = new ProjectFile(repoFile.getName(), repoFile.getBody(), folderName, repoFile.getLastCommitTimestamp());
                    infinispanService.saveProjectFile(file);
                });
            });
        } catch (Exception e) {
            LOGGER.error("Error during project import", e);
        }
    }

    public Project importProject(String projectId) {
        LOGGER.info("Import project from Git " + projectId);
        try {
            GitRepo repo = gitService.readProjectFromRepository(projectId);
            return importProjectFromRepo(repo);
        } catch (Exception e) {
            LOGGER.error("Error during project import", e);
            return null;
        }
    }

    private Project importProjectFromRepo(GitRepo repo) {
        LOGGER.info("Import project from GitRepo " + repo.getName());
        try {
            Project project = getProjectFromRepo(repo);
            infinispanService.saveProject(project);
            repo.getFiles().forEach(repoFile -> {
                ProjectFile file = new ProjectFile(repoFile.getName(), repoFile.getBody(), repo.getName(), repoFile.getLastCommitTimestamp());
                infinispanService.saveProjectFile(file);
            });
            return project;
        } catch (Exception e) {
            LOGGER.error("Error during project import", e);
            return null;
        }
    }

    public Project getProjectFromRepo(GitRepo repo) {
        String folderName = repo.getName();
        String propertiesFile = codeService.getPropertiesFile(repo);
        String projectName = codeService.getProjectName(propertiesFile);
        String projectDescription = codeService.getProjectDescription(propertiesFile);
        String runtime = codeService.getProjectRuntime(propertiesFile);
        return new Project(folderName, projectName, projectDescription, runtime, repo.getCommitId(), repo.getLastCommitTimestamp());
    }

    public Project commitAndPushProject(String projectId, String message) throws Exception {
        Project p = infinispanService.getProject(projectId);
        List<ProjectFile> files = infinispanService.getProjectFiles(projectId);
        RevCommit commit = gitService.commitAndPushProject(p, files, message);
        String commitId = commit.getId().getName();
        Long lastUpdate = commit.getCommitTime() * 1000L;
        p.setLastCommit(commitId);
        p.setLastCommitTimestamp(lastUpdate);
        infinispanService.saveProject(p);
        return p;
    }

    void addKameletsProject() {
        LOGGER.info("Add custom kamelets project if not exists");
        try {
            Project kamelets = infinispanService.getProject(Project.Type.kamelets.name());
            if (kamelets == null) {
                kamelets = new Project(Project.Type.kamelets.name(), "Custom Kamelets", "Custom Kamelets", "", "", Instant.now().toEpochMilli(), Project.Type.kamelets);
                infinispanService.saveProject(kamelets);
                commitAndPushProject(Project.Type.kamelets.name(), "Add custom kamelets");
            }
        } catch (Exception e) {
            LOGGER.error("Error during custom kamelets project creation", e);
        }
    }

    void addTemplatesProject() {
        LOGGER.info("Add templates project if not exists");
        try {
            Project templates = infinispanService.getProject(Project.Type.templates.name());
            if (templates == null) {
                templates = new Project(Project.Type.templates.name(), "Templates", "Templates", "", "", Instant.now().toEpochMilli(), Project.Type.templates);
                infinispanService.saveProject(templates);

                codeService.getTemplates().forEach((name, value) -> {
                    ProjectFile file = new ProjectFile(name, value, Project.Type.templates.name(), Instant.now().toEpochMilli());
                    infinispanService.saveProjectFile(file);
                });
                commitAndPushProject(Project.Type.templates.name(), "Add default templates");
            }
        } catch (Exception e) {
            LOGGER.error("Error during templates project creation", e);
        }
    }

    void addServicesProject() {
        LOGGER.info("Add services project if not exists");
        try {
            Project services = infinispanService.getProject(Project.Type.services.name());
            if (services == null) {
                services = new Project(Project.Type.services.name(), "Services", "Development Services", "", "", Instant.now().toEpochMilli(), Project.Type.services);
                infinispanService.saveProject(services);

                codeService.getServices().forEach((name, value) -> {
                    ProjectFile file = new ProjectFile(name, value, Project.Type.services.name(), Instant.now().toEpochMilli());
                    infinispanService.saveProjectFile(file);
                });
                commitAndPushProject(Project.Type.services.name(), "Add services");
            }
        } catch (Exception e) {
            LOGGER.error("Error during services project creation", e);
        }
    }

    public String getDevServiceCode() {
        List<ProjectFile> files = infinispanService.getProjectFiles(Project.Type.services.name());
        Optional<ProjectFile> file = files.stream().filter(f -> f.getName().equals(DEV_SERVICES_FILENAME)).findFirst();
        return file.orElse(new ProjectFile()).getCode();
    }

    public void setProjectImage(String projectId, String imageName, boolean commit, String message) throws Exception {
        ProjectFile file = infinispanService.getProjectFile(projectId, PROJECT_COMPOSE_FILENAME);
        if (file != null) {
            DockerComposeService service = DockerComposeConverter.fromCode(file.getCode(), projectId);
            service.setImage(imageName);
            String code = DockerComposeConverter.toCode(service);
            file.setCode(code);
            infinispanService.saveProjectFile(file);
            if (commit) {
                commitAndPushProject(projectId, message);
            }
        }
    }

    public DockerComposeService getProjectDockerComposeService(String projectId) {
        ProjectFile file = infinispanService.getProjectFile(projectId, PROJECT_COMPOSE_FILENAME);
        if (file != null) {
            return DockerComposeConverter.fromCode(file.getCode(), projectId);
        }
        return null;
    }
}
