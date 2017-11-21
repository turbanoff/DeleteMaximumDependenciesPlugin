package org.turbanov;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCoreUtil;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * @author Andrey Turbanov
 * @since 20.11.2017
 */
public class DeleteMaximumDependenciesAction extends AnAction {
    private static final Logger log = Logger.getInstance(DeleteMaximumDependenciesAction.class);
    private static final Integer betweenModuleSleepMills = Integer.getInteger("between.modules.sleep.mills", 0);

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
        Module module = anActionEvent.getData(LangDataKeys.MODULE);
        if (module == null) {
            return;
        }
        Project project = module.getProject();
        ModuleManager moduleManager = ModuleManager.getInstance(project);

        Collection<Module> moduleWithAllDependencies = collectDependencies(module);

        Module[] allModules = moduleManager.getModules();
        List<Module> toDispose = new ArrayList<>();
        for (Module nextModule : allModules) {
            if (!moduleWithAllDependencies.contains(nextModule)) {
                toDispose.add(nextModule);
            }
        }
        log.info("Should dispose this modules: " + toDispose);

        ModifiableModuleModel modifiableModel = moduleManager.getModifiableModel();
        ProgressManager.getInstance().run(new Task.Modal(project, "Removing Modules", true) {
            public void run(@NotNull ProgressIndicator indicator) {
                for (int i = 0; i < toDispose.size(); i++) {
                    Module moduleToDispose = toDispose.get(i);
                    indicator.setFraction((double)i / toDispose.size());
                    indicator.setText("Removing " + moduleToDispose);
                    ModuleRootManager rootManager = ModuleRootManager.getInstance(moduleToDispose);
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        deleteFiles(rootManager);
                        deleteFiles(rootManager);
                    });

                    try {
                        TimeUnit.MILLISECONDS.sleep(betweenModuleSleepMills);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    modifiableModel.disposeModule(moduleToDispose);
                }
            }
        });
    }

    private Collection<Module> collectDependencies(Module module) {
        ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
        Project project = module.getProject();
        THashSet<Module> resultSet = ContainerUtil.newIdentityTroveSet();
        Collections.addAll(resultSet, moduleRootManager.getDependencies());
        resultSet.add(module);

        MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
        Queue<Module> sourceModules = new ArrayDeque<>(resultSet);
        while (!sourceModules.isEmpty()) {
            Module sourceModule = sourceModules.poll();
            boolean isMavenized = manager.isMavenizedModule(sourceModule);
            if (!isMavenized) {
                continue;
            }
            MavenProject mavenProject = manager.findProject(sourceModule);
            if (mavenProject == null) {
                continue;
            }
            MavenId parentId = mavenProject.getParentId();
            if (parentId == null) {
                continue;
            }
            MavenProject parentProject = manager.findProject(parentId);
            if (parentProject == null) {
                continue;
            }

            Module parentModule = manager.findModule(parentProject);
            if (parentModule == null) {
                continue;
            }
            if (resultSet.add(parentModule)) {
                sourceModules.add(parentModule);
            }
        }
        return resultSet;
    }

    private void deleteFiles(ModuleRootManager rootManager) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                rootManager.getFileIndex().iterateContent(new ContentIterator() {
                    @Override
                    public boolean processFile(VirtualFile virtualFile) {
                        if (ProjectCoreUtil.isProjectOrWorkspaceFile(virtualFile) && !virtualFile.getName().endsWith(".iml")) {
                            log.info("Skip file: " + virtualFile);
                            return true;
                        }
                        VirtualFile[] children = virtualFile.getChildren();
                        if (children == null || children.length == 0) {
                            log.info("Removing file: " + virtualFile);
                            try {
                                virtualFile.delete(this);
                            } catch (IOException e) {
                                log.warn(e);
                            }
                        }
                        return true;
                    }
                });
            }
        });
    }
}
