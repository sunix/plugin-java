/*******************************************************************************
 * Copyright (c) 2012-2014 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package com.codenvy.ide.extension.maven.client.wizard;

import com.codenvy.api.project.gwt.client.ProjectServiceClient;
import com.codenvy.api.project.shared.dto.ProjectDescriptor;
import com.codenvy.ide.api.event.OpenProjectEvent;
import com.codenvy.ide.api.projecttype.wizard.ProjectWizard;
import com.codenvy.ide.api.wizard.AbstractWizardPage;
import com.codenvy.ide.api.wizard.Wizard;
import com.codenvy.ide.collections.Jso;
import com.codenvy.ide.dto.DtoFactory;
import com.codenvy.ide.extension.maven.shared.MavenAttributes;
import com.codenvy.ide.rest.AsyncRequestCallback;
import com.codenvy.ide.rest.DtoUnmarshallerFactory;
import com.codenvy.ide.rest.StringUnmarshaller;
import com.codenvy.ide.rest.Unmarshallable;
import com.codenvy.ide.util.loging.Log;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.ui.AcceptsOneWidget;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Evgen Vidolob
 */
@Singleton
public class MavenPagePresenter extends AbstractWizardPage implements MavenPageView.ActionDelegate {

    protected MavenPageView          view;
    private ProjectServiceClient   projectServiceClient;
    protected EventBus               eventBus;
    private DtoFactory             dtoFactory;
    private DtoUnmarshallerFactory dtoUnmarshallerFactory;
    private MavenPomReaderClient   pomReaderClient;

    @Inject
    public MavenPagePresenter(MavenPageView view, ProjectServiceClient projectServiceClient, EventBus eventBus,
                              DtoFactory dtoFactory, DtoUnmarshallerFactory dtoUnmarshallerFactory, MavenPomReaderClient pomReaderClient) {
        super("Maven project settings", null);
        this.view = view;
        this.projectServiceClient = projectServiceClient;
        this.eventBus = eventBus;
        this.dtoFactory = dtoFactory;
        this.dtoUnmarshallerFactory = dtoUnmarshallerFactory;
        this.pomReaderClient = pomReaderClient;
        view.setDelegate(this);
    }

    @Nullable
    @Override
    public String getNotice() {
        return null;
    }

    @Override
    public boolean isCompleted() {
        return !view.getArtifactId().equals("") && !view.getGroupId().equals("") && !view.getVersion().equals("");
    }

    @Override
    public void focusComponent() {
    }

    @Override
    public void removeOptions() {
    }

    @Override
    public void setUpdateDelegate(@NotNull Wizard.UpdateDelegate delegate) {
        super.setUpdateDelegate(delegate);
    }

    @Override
    public void go(AcceptsOneWidget container) {
        container.setWidget(view);
        view.reset();
        ProjectDescriptor project = wizardContext.getData(ProjectWizard.PROJECT);
        if (project != null) {
            Map<String, List<String>> attributes = project.getAttributes();
            List<String> artifactIdAttr = attributes.get(MavenAttributes.MAVEN_ARTIFACT_ID);
            if (artifactIdAttr != null) {
                view.setArtifactId(artifactIdAttr.get(0));
                view.setGroupId(attributes.get(MavenAttributes.MAVEN_GROUP_ID).get(0));
                view.setVersion(attributes.get(MavenAttributes.MAVEN_VERSION).get(0));
                view.setPackaging(attributes.get(MavenAttributes.MAVEN_PACKAGING).get(0));
                scheduleTextChanges();
            } else {
                pomReaderClient.readPomAttributes(project.getPath(), new AsyncRequestCallback<String>(new StringUnmarshaller()) {
                    @Override
                    protected void onSuccess(String result) {
                        Jso jso = Jso.deserialize(result);
                        view.setArtifactId(jso.getStringField(MavenAttributes.MAVEN_ARTIFACT_ID));
                        view.setGroupId(jso.getStringField(MavenAttributes.MAVEN_GROUP_ID));
                        view.setVersion(jso.getStringField(MavenAttributes.MAVEN_VERSION));
                        view.setPackaging(jso.getStringField(MavenAttributes.MAVEN_PACKAGING));
                        scheduleTextChanges();
                    }

                    @Override
                    protected void onFailure(Throwable exception) {
                        Log.error(MavenPagePresenter.class, exception);
                    }
                });
            }
            wizardContext.putData(ProjectWizard.BUILDER_NAME, "maven");
        }
    }

    private void scheduleTextChanges() {
        Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
            @Override
            public void execute() {
                onTextsChange();
            }
        });
    }

    @Override
    public void commit(@NotNull final CommitCallback callback) {
        Map<String, List<String>> options = new HashMap<>();
        options.put(MavenAttributes.MAVEN_ARTIFACT_ID, Arrays.asList(view.getArtifactId()));
        options.put(MavenAttributes.MAVEN_GROUP_ID, Arrays.asList(view.getGroupId()));
        options.put(MavenAttributes.MAVEN_VERSION, Arrays.asList(view.getVersion()));
        options.put(MavenAttributes.MAVEN_PACKAGING, Arrays.asList(view.getPackaging()));

        final ProjectDescriptor projectDescriptorToUpdate = dtoFactory.createDto(ProjectDescriptor.class);
        projectDescriptorToUpdate.withProjectTypeId(wizardContext.getData(ProjectWizard.PROJECT_TYPE).getProjectTypeId());
        projectDescriptorToUpdate.setAttributes(options);
        boolean visibility = wizardContext.getData(ProjectWizard.PROJECT_VISIBILITY);
        projectDescriptorToUpdate.setVisibility(visibility ? "public" : "private");
        projectDescriptorToUpdate.setDescription(wizardContext.getData(ProjectWizard.PROJECT_DESCRIPTION));
        projectDescriptorToUpdate.setBuilder("maven");
        final String name = wizardContext.getData(ProjectWizard.PROJECT_NAME);
        final ProjectDescriptor project = wizardContext.getData(ProjectWizard.PROJECT);
        if (project != null) {
            if (project.getName().equals(name)) {
                updateProject(project, projectDescriptorToUpdate, callback);
            } else {
                projectServiceClient.rename(project.getPath(), name, null, new AsyncRequestCallback<Void>() {
                    @Override
                    protected void onSuccess(Void result) {
                        project.setName(name);

                        updateProject(project, projectDescriptorToUpdate, callback);
                    }

                    @Override
                    protected void onFailure(Throwable exception) {
                        callback.onFailure(exception);
                    }
                });
            }

        } else {
            createProject(callback, projectDescriptorToUpdate, name);
        }
    }

    private void updateProject(final ProjectDescriptor project, ProjectDescriptor projectDescriptorToUpdate,
                               final CommitCallback callback) {
        Unmarshallable<ProjectDescriptor> unmarshaller = dtoUnmarshallerFactory.newUnmarshaller(ProjectDescriptor.class);
        projectServiceClient
                .updateProject(project.getPath(), projectDescriptorToUpdate, new AsyncRequestCallback<ProjectDescriptor>(unmarshaller) {
                    @Override
                    protected void onSuccess(ProjectDescriptor result) {
                        eventBus.fireEvent(new OpenProjectEvent(result.getName()));
                        wizardContext.putData(ProjectWizard.PROJECT, result);
                        callback.onSuccess();
                    }

                    @Override
                    protected void onFailure(Throwable exception) {
                        callback.onFailure(exception);
                    }
                });
    }

    private void createProject(final CommitCallback callback, ProjectDescriptor projectDescriptor, final String name) {
        projectServiceClient
                .createProject(name, projectDescriptor,
                               new AsyncRequestCallback<ProjectDescriptor>(
                                       dtoUnmarshallerFactory.newUnmarshaller(ProjectDescriptor.class)) {
                                   @Override
                                   protected void onSuccess(ProjectDescriptor result) {
                                       eventBus.fireEvent(new OpenProjectEvent(result.getName()));
                                       wizardContext.putData(ProjectWizard.PROJECT, result);
                                       callback.onSuccess();
                                   }

                                   @Override
                                   protected void onFailure(Throwable exception) {
                                       callback.onFailure(exception);
                                   }
                               }
                              );
    }

    @Override
    public void onTextsChange() {
        delegate.updateControls();
    }

    @Override
    public void setPackaging(String packaging) {
        if ("war".equals(packaging)) {
//            options.put(Constants.RUNNER_NAME, Arrays.asList("JavaWeb"));
            wizardContext.putData(ProjectWizard.RUNNER_NAME, "JavaWeb");
        }
        if ("jar".equals(packaging)) {
//            options.put(Constants.RUNNER_NAME, Arrays.asList("JavaStandalone"));
            wizardContext.putData(ProjectWizard.RUNNER_NAME, "JavaStandalone");
        }
    }
}
