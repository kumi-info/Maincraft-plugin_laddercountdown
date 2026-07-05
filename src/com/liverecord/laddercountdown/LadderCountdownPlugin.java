package com.liverecord.laddercountdown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * s2e-ladder のカウントダウン秒数をコマンドで変更する補助プラグイン（s2e-ladder 専用）。
 *  - /laddercount <秒>            : カウントダウン秒数を変更（実行中なら即反映）
 *  - /laddercount                 : 現在値を表示
 *  - /laddercount autoreset on/off: 起動時の自動リセットを切替
 *
 * ※ Farm 用は別プラグイン FarmCountdown が担当する。本プラグインは s2e-ladder 専用。
 */
public final class LadderCountdownPlugin extends JavaPlugin {

    private static final String LADDER_PLUGIN = "s2e-ladder";
    private static final String LISTENERS_CLASS = "streamtoearn.io.s2eLadder.managers.ListenersManager";
    private static final String RUNNABLE_CLASS = "streamtoearn.io.s2eLadder.managers.timer.TimerManager$1";
    private static final String CONFIG_PATH = "settings.timer.countdown";
    private static final int MIN = 1;
    private static final int MAX = 3600;
    /** 自動リセット時に戻す固定のデフォルト秒数。 */
    private static final int DEFAULT_COUNTDOWN = 15;

    /** サーバー起動時に countdown をデフォルトへ自動リセットするか（config.yml reset-on-start）。 */
    private boolean resetOnStart;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true);
        }
        resetOnStart = getConfig().getBoolean("reset-on-start", false);
        if (resetOnStart) {
            applyDefaultReset();
        }
        removeNamespacedAlias();
        getLogger().info("LadderCountdown 有効化（s2e-ladder専用）。/laddercount が利用可能。"
                + "（起動時自動リセット: " + (resetOnStart ? "ON→" + DEFAULT_COUNTDOWN + "秒" : "OFF") + "）");
    }

    @SuppressWarnings("unchecked")
    private void removeNamespacedAlias() {
        try {
            var commandMap = Bukkit.getServer().getCommandMap();
            Field f = commandMap.getClass().getDeclaredField("knownCommands");
            f.setAccessible(true);
            Map<String, Command> known = (Map<String, Command>) f.get(commandMap);
            known.remove("laddercountdown:laddercount");
            try {
                Method sync = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
                sync.setAccessible(true);
                sync.invoke(Bukkit.getServer());
            } catch (Exception ignored) {
            }
        } catch (Exception ignore) {
        }
    }

    /** timer.yml の countdown を固定デフォルト(15秒)へ書き戻す。 */
    private void applyDefaultReset() {
        boolean ok = writeCountdownToFile(DEFAULT_COUNTDOWN);
        getLogger().info("[countdown] 起動時自動リセット: timer.yml の countdown を "
                + DEFAULT_COUNTDOWN + " 秒へ" + (ok ? "戻しました。" : "戻せませんでした（書込失敗）。"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("laddercount")) {
            return handleCountdown(sender, args);
        }
        return false;
    }

    // ===================== /countdown =====================

    private boolean handleCountdown(CommandSender sender, String[] args) {
        if (!sender.hasPermission("laddercountdown.set")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("autoreset") || args[0].equalsIgnoreCase("auto"))) {
            return handleAutoReset(sender, args);
        }
        if (args.length == 0) {
            Integer current = readCountdownFromFile();
            sender.sendMessage("§e現在の countdown: §f"
                    + (current == null ? "不明" : current + " 秒"));
            sender.sendMessage("§7使い方: /laddercount <秒>  (例: /laddercount 15)");
            sender.sendMessage("§7起動時自動リセット: " + (resetOnStart ? "§aON§7" : "§eOFF§7")
                    + " (/laddercount autoreset on|off)");
            return true;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException e) {
            sender.sendMessage("§c数値を入力してください。例: /laddercount 15");
            return true;
        }
        if (seconds < MIN || seconds > MAX) {
            sender.sendMessage("§c" + MIN + "〜" + MAX + " の範囲で指定してください。");
            return true;
        }

        boolean fileOk = writeCountdownToFile(seconds);
        String applyResult = applyToRunningInstance(seconds);
        showCountdownTitle(seconds);

        if (fileOk) {
            sender.sendMessage("§acountdown を §f" + seconds + " 秒§a に設定しました。");
        } else {
            sender.sendMessage("§etimer.yml の保存に失敗しました（ログ参照）。");
        }
        sender.sendMessage("§7" + applyResult);
        return true;
    }

    /** /countdown autoreset <on|off|status> の処理。状態は config.yml に永続化する。 */
    private boolean handleAutoReset(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("§e起動時自動リセット: " + (resetOnStart ? "§aON" : "§eOFF")
                    + " §7(リセット先: " + DEFAULT_COUNTDOWN + " 秒)");
            sender.sendMessage("§7切替: /laddercount autoreset on|off");
            return true;
        }
        String v = args[1].toLowerCase(Locale.ROOT);
        boolean enable;
        if (v.equals("on") || v.equals("true") || v.equals("enable")) {
            enable = true;
        } else if (v.equals("off") || v.equals("false") || v.equals("disable")) {
            enable = false;
        } else {
            sender.sendMessage("§con / off で指定してください。例: /laddercount autoreset on");
            return true;
        }
        resetOnStart = enable;
        getConfig().set("reset-on-start", enable);
        saveConfig();
        if (enable) {
            sender.sendMessage("§a再起動時の自動リセットを ON にしました。"
                    + "（サーバー起動時に countdown を " + DEFAULT_COUNTDOWN + " 秒へ戻します）");
        } else {
            sender.sendMessage("§e再起動時の自動リセットを OFF にしました。"
                    + "（変更した秒数が再起動後も保持されます）");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("laddercount")) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"autoreset", "15", "20", "30"}) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("autoreset") || args[0].equalsIgnoreCase("auto"))) {
            String p = args[1].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"on", "off", "status"}) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
            return out;
        }
        return out;
    }

    /**
     * カウントダウン変更時に、全プレイヤーの画面中央へ太字タイトルと効果音を出す。
     * 10秒超は赤＋低音、10秒以下は緑＋高音。
     */
    private void showCountdownTitle(int seconds) {
        boolean urgent = seconds <= 10;
        NamedTextColor color = urgent ? NamedTextColor.GREEN : NamedTextColor.RED;
        Component main = Component.text("タイマー " + seconds + "秒")
                .color(color)
                .decorate(TextDecoration.BOLD);
        Title.Times times = Title.Times.times(
                Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(500));
        Title title = Title.title(Component.empty(), main, times);

        Sound sound = urgent ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS;
        float pitch = urgent ? 1.5f : 0.8f;

        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), sound, 1.0f, pitch);
        }
    }

    private File ladderTimerFile() {
        Plugin ladder = getServer().getPluginManager().getPlugin(LADDER_PLUGIN);
        File dataFolder = (ladder != null)
                ? ladder.getDataFolder()
                : new File(getDataFolder().getParentFile(), LADDER_PLUGIN);
        return new File(dataFolder, "timer.yml");
    }

    private Integer readCountdownFromFile() {
        File file = ladderTimerFile();
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.contains(CONFIG_PATH)) {
            return null;
        }
        return cfg.getInt(CONFIG_PATH);
    }

    private boolean writeCountdownToFile(int seconds) {
        File file = ladderTimerFile();
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            cfg.set(CONFIG_PATH, seconds);
            cfg.save(file);
            return true;
        } catch (Exception e) {
            getLogger().warning("timer.yml の書き込みに失敗: " + e);
            return false;
        }
    }

    /**
     * 起動中の s2e-ladder に countdown を反映する。
     *  1) キャッシュ済み TimerSettings を新値で差し替え
     *  2) 動作中のカウントダウンがあれば Runnable の time フィールドを直接書き換えて即反映
     */
    private String applyToRunningInstance(int seconds) {
        Plugin ladder = getServer().getPluginManager().getPlugin(LADDER_PLUGIN);
        if (ladder == null || !ladder.isEnabled()) {
            return "s2e-ladder が無効のため、サーバー再起動時にファイル値が反映されます。";
        }
        try {
            Object listenersManager = findListenersManager(ladder);
            if (listenersManager == null) {
                return "実行中インスタンスが見つからず、再起動時に反映されます。";
            }
            Object timerManager = readField(listenersManager, "timer");
            if (timerManager == null) {
                return "TimerManager 未初期化。再起動時に反映されます。";
            }
            Object timerSettings = readField(timerManager, "settings");
            if (timerSettings == null) {
                return "TimerSettings 未初期化。再起動時に反映されます。";
            }
            replaceCountdown(timerManager, timerSettings, seconds);

            Object task = readField(timerManager, "countdownTask");
            boolean cancelled = !(task instanceof BukkitTask) || ((BukkitTask) task).isCancelled();
            if (task == null || cancelled) {
                return seconds + " 秒に変えました。";
            }

            Object runnable = findValueByClassName(task, RUNNABLE_CLASS, 3);
            if (runnable != null) {
                setIntField(runnable, "time", seconds);
                return "実行中のカウントダウンを即 " + seconds + " 秒にしました。";
            }

            Method start = timerManager.getClass().getMethod("start");
            start.setAccessible(true);
            start.invoke(timerManager);
            return "実行中のカウントダウンを再スタートして " + seconds + " 秒にしました。";
        } catch (Throwable t) {
            getLogger().warning("実行中インスタンスへの反映に失敗: " + t);
            return "実行中への反映に失敗しました。サーバー再起動時にファイル値が反映されます。";
        }
    }

    private Object findListenersManager(Plugin ladder) {
        List<RegisteredListener> listeners = HandlerList.getRegisteredListeners(ladder);
        for (RegisteredListener rl : listeners) {
            Listener l = rl.getListener();
            if (l != null && LISTENERS_CLASS.equals(l.getClass().getName())) {
                return l;
            }
        }
        return null;
    }

    private Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    /**
     * root オブジェクトのフィールドを幅優先で辿り、指定クラス名のインスタンスを探す。
     */
    private Object findValueByClassName(Object root, String className, int maxDepth) {
        if (root == null) {
            return null;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        seen.add(root);
        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            Object obj = cur[0];
            int depth = (Integer) cur[1];
            if (obj == null) {
                continue;
            }
            if (className.equals(obj.getClass().getName())) {
                return obj;
            }
            if (depth >= maxDepth) {
                continue;
            }
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    if ("next".equals(f.getName())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null && !seen.contains(v)) {
                            seen.add(v);
                            queue.add(new Object[]{v, depth + 1});
                        }
                    } catch (Throwable ignore) {
                        // アクセス不可フィールドは無視
                    }
                }
            }
        }
        return null;
    }

    /**
     * TimerSettings は record（不変）なので、既存値を引き継ぎ countdown だけ差し替えた
     * 新インスタンスを生成し、TimerManager.settings へ代入する。
     */
    private void replaceCountdown(Object timerManager, Object timerSettings, int seconds) throws Exception {
        Class<?> tsClass = timerSettings.getClass();
        RecordComponent[] comps = tsClass.getRecordComponents();
        if (comps == null) {
            throw new IllegalStateException(tsClass.getName() + " は record ではありません");
        }
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            if ("countdown".equals(comps[i].getName())) {
                args[i] = seconds;
            } else {
                Method accessor = comps[i].getAccessor();
                accessor.setAccessible(true);
                args[i] = accessor.invoke(timerSettings);
            }
        }
        Constructor<?> ctor = tsClass.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        Object newSettings = ctor.newInstance(args);

        Field settingsField = timerManager.getClass().getDeclaredField("settings");
        settingsField.setAccessible(true);
        settingsField.set(timerManager, newSettings);
    }
}
