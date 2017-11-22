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
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        List<VirtualFile> toDeleteTests = new ArrayList<>();
        for (Module nextModule : allModules) {
            if (!moduleWithAllDependencies.contains(nextModule)) {
                toDispose.add(nextModule);
            } else if (nextModule != module) {
                ModuleRootManager rootManager = ModuleRootManager.getInstance(nextModule);
                List<VirtualFile> testRoots = rootManager.getSourceRoots(JavaSourceRootType.TEST_SOURCE);
                toDeleteTests.addAll(testRoots);
            }
        }
        log.info("Should dispose this modules: " + toDispose);
        log.info("Should delete this test roots: " + toDeleteTests);

        int sumSize = toDispose.size() + toDeleteTests.size();
        ModifiableModuleModel modifiableModel = moduleManager.getModifiableModel();
        ProgressManager.getInstance().run(new Task.Modal(project, "Removing Modules", true) {
            public void run(@NotNull ProgressIndicator indicator) {
                for (int i = 0; i < toDispose.size(); i++) {
                    Module moduleToDispose = toDispose.get(i);
                    indicator.setFraction((double)i / sumSize);
                    indicator.setText("Removing " + moduleToDispose);
                    ModuleRootManager rootManager = ModuleRootManager.getInstance(moduleToDispose);
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        int maximum = 40;
                        for (int j = 0; j < maximum; j++) {
                            if (!deleteFiles(rootManager)) {
                                break;
                            }
                        }
                    });

                    try {
                        TimeUnit.MILLISECONDS.sleep(betweenModuleSleepMills);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    modifiableModel.disposeModule(moduleToDispose);
                }

                ApplicationManager.getApplication().invokeAndWait(() -> {
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        for (int i = 0; i < toDeleteTests.size(); i++) {
                            VirtualFile next = toDeleteTests.get(i);
                            indicator.setFraction((double) (i + toDispose.size()) / sumSize);
                            indicator.setText("Removing test root: " + next);
                            deleteFile(next);
                            try {
                                TimeUnit.MILLISECONDS.sleep(betweenModuleSleepMills);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                });
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
            if (!manager.isMavenizedModule(sourceModule)) {
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

    private boolean deleteFiles(ModuleRootManager rootManager) {
        Boolean result = ApplicationManager.getApplication().runWriteAction((Computable<Boolean>) () -> {
            AtomicBoolean result1 = new AtomicBoolean(false);
            rootManager.getFileIndex().iterateContent(virtualFile -> {
                if (ProjectCoreUtil.isProjectOrWorkspaceFile(virtualFile) && !virtualFile.getName().endsWith(".iml")) {
                    log.info("Skip file: " + virtualFile);
                    return true;
                }
                VirtualFile[] children = virtualFile.getChildren();
                if (children == null || children.length == 0) {
                    boolean deleted = deleteFile(virtualFile);
                    if (deleted) {
                        result1.set(true);
                    }
                }
                return true;
            });
            return result1.get();
        });
        return result;
    }

    private boolean deleteFile(VirtualFile virtualFile) {
        log.info("Removing file: " + virtualFile);
        try {
            virtualFile.delete(this);
            return true;
        } catch (IOException e) {
            log.warn(e);
            return false;
        }
    }
}
