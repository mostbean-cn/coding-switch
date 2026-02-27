package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.Skill;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Skills 管理服务。
 * 管理 Claude Skills 的发现、安装和卸载。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchSkills", storages = @Storage("coding-switch-skills.xml"))
public final class SkillService implements PersistentStateComponent<SkillService.State> {

    private static final Logger LOG = Logger.getInstance(SkillService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 内置推荐的 Skills 仓库列表 */
    public static final List<String> DEFAULT_REPOS = List.of(
            "https://github.com/anthropics/courses",
            "https://github.com/anthropics/prompt-eng-interactive-tutorial",
            "https://github.com/ComposioHQ/composio");

    public static class State {
        public String skillsJson = "[]";
        public String customReposJson = "[]";
    }

    private State myState = new State();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public static SkillService getInstance() {
        return ApplicationManager.getApplication().getService(SkillService.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    // =====================================================================
    // Skills CRUD
    // =====================================================================

    public List<Skill> getSkills() {
        try {
            List<Skill> list = GSON.fromJson(myState.skillsJson,
                    new TypeToken<List<Skill>>() {
                    }.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            LOG.warn("Failed to parse skills", e);
            return new ArrayList<>();
        }
    }

    public List<Skill> getInstalledSkills() {
        return getSkills().stream().filter(Skill::isInstalled).toList();
    }

    /**
     * 扫描本地 ~/.claude/skills/ 目录中已安装的 Skills。
     */
    public List<Skill> scanLocalSkills() {
        List<Skill> localSkills = new ArrayList<>();
        Path skillsDir = ConfigFileService.getInstance().getSkillsDir();

        if (!Files.isDirectory(skillsDir)) {
            return localSkills;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Skill skill = new Skill();
                    skill.setName(entry.getFileName().toString());
                    skill.setInstalled(true);
                    skill.setLocalPath(entry.toString());
                    // 尝试读取 README 作为描述
                    Path readme = entry.resolve("README.md");
                    if (Files.exists(readme)) {
                        String content = Files.readString(readme);
                        skill.setDescription(content.length() > 200 ? content.substring(0, 200) + "..." : content);
                    }
                    localSkills.add(skill);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan skills directory", e);
        }

        return localSkills;
    }

    /**
     * 卸载指定 Skill（删除本地目录）。
     */
    public void uninstallSkill(String skillId) throws IOException {
        List<Skill> skills = new ArrayList<>(getSkills());
        for (Skill skill : skills) {
            if (skill.getId().equals(skillId) && skill.getLocalPath() != null) {
                Path path = Path.of(skill.getLocalPath());
                if (Files.exists(path)) {
                    deleteRecursively(path);
                }
                skill.setInstalled(false);
                skill.setLocalPath(null);
                break;
            }
        }
        saveSkills(skills);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    public void updateSkill(Skill skill) {
        List<Skill> skills = new ArrayList<>(getSkills());
        boolean found = false;
        for (int i = 0; i < skills.size(); i++) {
            if (skills.get(i).getId().equals(skill.getId())) {
                skills.set(i, skill);
                found = true;
                break;
            }
        }
        // 对于通过点击发现直接修改的对象
        if (!found) {
            for (int i = 0; i < skills.size(); i++) {
                if (skills.get(i).getName().equals(skill.getName())) {
                    skills.set(i, skill);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            skills.add(skill);
        }
        saveSkills(skills);
    }

    public boolean syncLocalSkills(List<Skill> localSkills) {
        List<Skill> saved = new ArrayList<>(getSkills());
        boolean changed = false;

        for (Skill local : localSkills) {
            boolean found = false;
            for (Skill ext : saved) {
                if (ext.getName().equals(local.getName())) {
                    ext.setInstalled(true);
                    ext.setLocalPath(local.getLocalPath());
                    if (ext.getDescription() == null || ext.getDescription().isBlank()) {
                        ext.setDescription(local.getDescription());
                    }
                    found = true;
                    changed = true;
                    break;
                }
            }
            if (!found) {
                saved.add(local);
                changed = true;
            }
        }

        for (Skill ext : saved) {
            if (ext.isInstalled()) {
                boolean stillThere = localSkills.stream().anyMatch(l -> l.getName().equals(ext.getName()));
                if (!stillThere) {
                    ext.setInstalled(false);
                    ext.setLocalPath(null);
                    changed = true;
                }
            }
        }

        if (changed) {
            saveSkills(saved);
        }
        return changed;
    }

    // =====================================================================
    // 自定义仓库管理
    // =====================================================================

    public List<String> getCustomRepos() {
        try {
            List<String> list = GSON.fromJson(myState.customReposJson,
                    new TypeToken<List<String>>() {
                    }.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void addCustomRepo(String repoUrl) {
        List<String> repos = new ArrayList<>(getCustomRepos());
        if (!repos.contains(repoUrl)) {
            repos.add(repoUrl);
            myState.customReposJson = GSON.toJson(repos);
            fireChanged();
        }
    }

    public void removeCustomRepo(String repoUrl) {
        List<String> repos = new ArrayList<>(getCustomRepos());
        repos.remove(repoUrl);
        myState.customReposJson = GSON.toJson(repos);
        fireChanged();
    }

    public List<String> getAllRepos() {
        List<String> all = new ArrayList<>(DEFAULT_REPOS);
        all.addAll(getCustomRepos());
        return all;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private void saveSkills(List<Skill> skills) {
        myState.skillsJson = GSON.toJson(skills);
        fireChanged();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
