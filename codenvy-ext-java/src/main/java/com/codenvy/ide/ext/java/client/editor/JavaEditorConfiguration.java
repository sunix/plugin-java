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
package com.codenvy.ide.ext.java.client.editor;

import com.codenvy.api.analytics.logger.AnalyticsEventLogger;
import com.codenvy.ide.api.editor.TextEditorPartPresenter;
import com.codenvy.ide.api.notification.NotificationManager;
import com.codenvy.ide.api.text.Document;
import com.codenvy.ide.api.texteditor.AutoEditStrategy;
import com.codenvy.ide.api.texteditor.ContentFormatter;
import com.codenvy.ide.api.texteditor.TextEditorConfiguration;
import com.codenvy.ide.api.texteditor.TextEditorPartView;
import com.codenvy.ide.api.texteditor.codeassistant.CodeAssistProcessor;
import com.codenvy.ide.api.texteditor.outline.OutlineModel;
import com.codenvy.ide.api.texteditor.parser.BasicTokenFactory;
import com.codenvy.ide.api.texteditor.parser.CmParser;
import com.codenvy.ide.api.texteditor.parser.Parser;
import com.codenvy.ide.api.texteditor.quickassist.QuickAssistProcessor;
import com.codenvy.ide.api.texteditor.reconciler.Reconciler;
import com.codenvy.ide.api.texteditor.reconciler.ReconcilerImpl;
import com.codenvy.ide.collections.Collections;
import com.codenvy.ide.collections.StringMap;
import com.codenvy.ide.ext.java.client.JavaLocalizationConstant;
import com.codenvy.ide.ext.java.client.JavaResources;
import com.codenvy.ide.ext.java.client.editor.outline.JavaNodeRenderer;
import com.codenvy.ide.ext.java.jdt.JavaPartitions;
import com.codenvy.ide.ext.java.jdt.internal.ui.text.BracketInserter;
import com.codenvy.ide.ext.java.jdt.internal.ui.text.JavaAutoEditStrategy;
import com.codenvy.ide.ext.java.jdt.internal.ui.text.JavaDocAutoIndentStrategy;
import com.codenvy.ide.ext.java.jdt.internal.ui.text.JavaStringAutoIndentStrategy;
import com.codenvy.ide.ext.java.jdt.internal.ui.text.SmartSemicolonAutoEditStrategy;
import com.codenvy.ide.texteditor.TextEditorViewImpl;
import com.codenvy.ide.util.executor.BasicIncrementalScheduler;
import com.codenvy.ide.util.executor.UserActivityManager;

import javax.validation.constraints.NotNull;

/**
 * Java specific configuration of the editor
 *
 * @author Evgen Vidolob
 */
public class JavaEditorConfiguration extends TextEditorConfiguration {

    private UserActivityManager      manager;
    private TextEditorPartPresenter  javaEditor;
    private JavaCodeAssistProcessor  codeAssistProcessor;
    private JavaReconcilerStrategy   reconcilerStrategy;
    private OutlineModel             outlineModel;
    private String                   documentPartitioning;
    private JavaParserWorker         worker;
    private JavaResources            javaResources;
    private AnalyticsEventLogger     eventLogger;
    private JavaLocalizationConstant localizationConstant;
    private ContentFormatter         contentFormatter;

    public JavaEditorConfiguration(UserActivityManager manager,
                                   JavaResources resources,
                                   TextEditorPartPresenter javaEditor,
                                   String documentPartitioning,
                                   JavaParserWorker worker,
                                   ContentFormatter contentFormatter,
                                   AnalyticsEventLogger eventLogger,
                                   NotificationManager notificationManager,
                                   JavaLocalizationConstant localizationConstant) {
        this.manager = manager;
        this.javaEditor = javaEditor;
        this.documentPartitioning = documentPartitioning;
        this.worker = worker;
        this.javaResources = resources;
        this.eventLogger = eventLogger;
        this.localizationConstant = localizationConstant;
        outlineModel = new OutlineModel(new JavaNodeRenderer(resources));
        codeAssistProcessor = new JavaCodeAssistProcessor(javaEditor, worker, javaResources, eventLogger);
        reconcilerStrategy = new JavaReconcilerStrategy(javaEditor, worker, outlineModel, notificationManager, codeAssistProcessor,
                                                        localizationConstant);
        this.contentFormatter = contentFormatter;


    }

    /** {@inheritDoc} */
    @Override
    public Parser getParser(TextEditorPartView view) {
        CmParser parser = getParserForMime("text/x-java");
        parser.setNameAndFactory("clike", new BasicTokenFactory());
        return parser;
    }

    /** {@inheritDoc} */
    @Override
    public Reconciler getReconciler(TextEditorPartView view) {
        BasicIncrementalScheduler scheduler = new BasicIncrementalScheduler(manager, 50, 100);
        ReconcilerImpl reconciler = new ReconcilerImpl(Document.DEFAULT_PARTITIONING, scheduler);
        reconciler.addReconcilingStrategy(Document.DEFAULT_CONTENT_TYPE, reconcilerStrategy);
        return reconciler;
    }

    /** {@inheritDoc} */
    @Override
    public StringMap<CodeAssistProcessor> getContentAssistantProcessors(TextEditorPartView view) {

        StringMap<CodeAssistProcessor> map = Collections.createStringMap();
        map.put(Document.DEFAULT_CONTENT_TYPE, codeAssistProcessor);
        return map;
    }

    /** {@inheritDoc} */
    @Override
    public QuickAssistProcessor getQuickAssistAssistant(TextEditorPartView view) {
        JavaCorrectionAssistant assistant = new JavaCorrectionAssistant(javaEditor, worker, javaResources);
        assistant.install(view);
        ((TextEditorViewImpl)view).setQuickAssistAssistant(assistant);
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public OutlineModel getOutline(TextEditorPartView view) {
        return outlineModel;
    }

    @Override
    public String[] getConfiguredContentTypes(TextEditorPartView view) {
        return new String[]{
                Document.DEFAULT_CONTENT_TYPE,
                JavaPartitions.JAVA_DOC,
                JavaPartitions.JAVA_MULTI_LINE_COMMENT,
                JavaPartitions.JAVA_SINGLE_LINE_COMMENT,
                JavaPartitions.JAVA_STRING,
                JavaPartitions.JAVA_CHARACTER
        };
    }

    @Override
    public AutoEditStrategy[] getAutoEditStrategies(TextEditorPartView view, String contentType) {
        String partitioning = getConfiguredDocumentPartitioning(view);
        if (JavaPartitions.JAVA_DOC.equals(contentType) || JavaPartitions.JAVA_MULTI_LINE_COMMENT.equals(contentType))
            return new AutoEditStrategy[]{new JavaDocAutoIndentStrategy(partitioning)};
        else if (JavaPartitions.JAVA_STRING.equals(contentType))
            return new AutoEditStrategy[]{new SmartSemicolonAutoEditStrategy(partitioning), new JavaStringAutoIndentStrategy(partitioning)};
        else if (JavaPartitions.JAVA_CHARACTER.equals(contentType) || Document.DEFAULT_CONTENT_TYPE.equals(contentType))
            return new AutoEditStrategy[]{new SmartSemicolonAutoEditStrategy(partitioning),
                                          new JavaAutoEditStrategy(partitioning),
                                          new BracketInserter(view)};
        else
            return new AutoEditStrategy[]{new JavaAutoEditStrategy(partitioning)};
    }

    @Override
    public String getConfiguredDocumentPartitioning(TextEditorPartView view) {
        if (documentPartitioning != null)
            return documentPartitioning;
        return super.getConfiguredDocumentPartitioning(view);
    }

    @Override
    public ContentFormatter getContentFormatter(@NotNull TextEditorPartView view) {
        return contentFormatter;
    }
}
