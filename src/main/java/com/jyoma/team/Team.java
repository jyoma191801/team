package com.jyoma.team;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.scoreboard.Scoreboard;

import net.md_5.bungee.api.chat.*;
import net.md_5.bungee.api.ChatColor;
import org.jetbrains.annotations.NotNull;

import io.papermc.paper.event.player.AsyncChatEvent;

import java.util.*;
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class Team extends JavaPlugin implements Listener, TabCompleter {

    private String T = "§x§6§7§6§E§D§6[§x§6§E§7§8§D§6T§x§7§6§8§2§D§6E§x§7§D§8§C§D§6A§x§8§5§9§6§D§6M§x§8§C§A§0§D§6]§f";

    private Scoreboard scoreboard;
    private final Map<UUID, String> roleMap = new HashMap<>();
    private final Map<UUID, Boolean> teamChatMode = new HashMap<>();
    private final Map<String, Boolean> teamPvp = new HashMap<>();
    private final Map<UUID, String> inviteMap = new HashMap<>();

    // ...existing code...

    private File dataFile;

    // 초대 쿨다운: 초대한사람UUID:대상UUID -> 마지막 초대 시각(ms)
    private final Map<String, Long> inviteCooldowns = new HashMap<>();
    private static final long INVITE_COOLDOWN_MS = 30 * 1000L; // 30초

    @Override
    public void onEnable() {
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("팀")).setExecutor(this);
        Objects.requireNonNull(getCommand("팀")).setTabCompleter(this);

        // 데이터 파일
        dataFile = new File(getDataFolder(), "teaminfo.yml");
        if (!getDataFolder().exists()) {
            if (!getDataFolder().mkdirs()) {
                getLogger().warning("Could not create data folder: " + getDataFolder().getAbsolutePath());
            }
        }

        // teaminfo.yml이 없으면 생성하고 기본 섹션으로 초기화
        if (!dataFile.exists()) {
            try {
                if (dataFile.createNewFile()) {
                    FileConfiguration empty = YamlConfiguration.loadConfiguration(dataFile);
                    // 빈 구조로 초기화
                    empty.set("teams", null);
                    empty.set("roles", null);
                    empty.set("teamChatMode", null);
                    empty.set("invites", null);
                    empty.save(dataFile);
                    getLogger().info("Created default teaminfo.yml");
                }
            } catch (IOException e) {
                getLogger().warning("Could not create teaminfo.yml: " + e.getMessage());
            }
        }

        loadTeamData();

        // 자동 저장 (비동기) - 5분마다 저장
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveTeamData, 20L * 60L * 5L, 20L * 60L * 5L);
    }

    @Override
    public void onDisable() {
        saveTeamData();
    }

    /* ===============================
                명령어 처리
       =============================== */

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {

        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        if (args.length == 0) return true;

        org.bukkit.scoreboard.Team team = getPlayerTeam(p);

        switch (args[0]) {

            case "인원목록":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                showTeamMembers(p, team);
                break;

            case "채팅":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                toggleTeamChat(p);
                break;

            case "초대":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                if (!canInvite(p)) {
                    p.sendMessage(T+"§c팀장과 부팀장만 초대할 수 있습니다.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(T+"§c/팀 초대 [닉네임]");
                    return true;
                }
                invitePlayer(p, args[1]);
                break;

            case "탈퇴":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                // 팀장은 탈퇴 불가 (해산 사용)
                if (isLeader(p)) {
                    p.sendMessage(T+"§c팀장은 탈퇴할 수 없습니다. 팀을 해산하려면 /팀 해산 을 사용하세요.");
                    return true;
                }
                leaveTeam(p);
                p.sendMessage(T+"§a팀에서 탈퇴하였습니다.");
                break;

            case "추방":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                if (!canKick(p)) {
                    p.sendMessage(T+"§c팀장과 부팀장만 추방할 수 있습니다.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(T+"§c/팀 추방 [닉네임]");
                    return true;
                }
                kickPlayer(p, args[1]);
                break;

            case "pvp":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                if (!canInvite(p)) {
                    p.sendMessage(T+"§c팀장과 부팀장만 pvp 설정을 변경할 수 있습니다.");
                    return true;
                }
                togglePvp(p);
                break;

            case "해산":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                if (!isLeader(p)) {
                    p.sendMessage(T+"§c팀장만 팀을 해산할 수 있습니다.");
                    return true;
                }
                disbandTeam(p);
                break;

            case "생성":
                if (getPlayerTeam(p) != null) {
                    p.sendMessage(T+"§c이미 팀에 소속되어 있습니다.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(T+"§c/팀 생성 [팀이름]");
                    return true;
                }
                createTeam(p, args[1]);
                break;

            case "부팀장":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                if (!isLeader(p)) {
                    p.sendMessage(T+"§c팀장만 부팀장을 임명할 수 있습니다.");
                    return true;
                }
                if (args.length < 2) return true;
                promoteToSub(p, args[1]);
                break;

            case "강등":
                if (team == null) {
                    p.sendMessage(T+"§c팀에 속해있지 않습니다.");
                    return true;
                }
                if (!isLeader(p)) {
                    p.sendMessage(T+"§c팀장만 강등시킬 수 있습니다.");
                    return true;
                }
                if (args.length < 2) return true;
                demoteToMember(p, args[1]);
                break;

            case "수락":
                if (getPlayerTeam(p) != null) {
                    p.sendMessage(T+"§c이미 팀에 소속되어 있습니다.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(T+"§c/팀 수락 [팀이름]");
                    return true;
                }
                acceptInvite(p, args[1]);
                break;
            case "거절":
                if (getPlayerTeam(p) != null) {
                    p.sendMessage(T+"§c이미 팀에 소속되어 있습니다.");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(T+"§c/팀 거절 [팀이름]");
                    return true;
                }
                String targetTeam = args[1];
                String invitedTeam = inviteMap.get(p.getUniqueId());
                if (invitedTeam == null || !invitedTeam.equals(targetTeam)) {
                    p.sendMessage(T+"§c해당 팀의 초대가 없습니다.");
                } else {
                    inviteMap.remove(p.getUniqueId());
                    saveTeamData();
                    p.sendMessage(T+"팀 가입 초대를 거절하였습니다.");
                }
                break;
            default:
                p.sendMessage(T+"§c알 수 없는 명령입니다.");
                break;
        }

        return true;
    }

    /* ===============================
                팀 채팅
       =============================== */

    private void toggleTeamChat(Player p) {
        boolean mode = teamChatMode.getOrDefault(p.getUniqueId(), false);
        teamChatMode.put(p.getUniqueId(), !mode);
        p.sendMessage(!mode ? "팀 채팅 §a§lON" : "팀 채팅 §c§lOFF");
        saveTeamData();
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();

        if (!teamChatMode.getOrDefault(p.getUniqueId(), false)) return;

        org.bukkit.scoreboard.Team team = getPlayerTeam(p);
        if (team == null) return;

        e.setCancelled(true);

        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayer(entry);
            if (member != null)
                member.sendMessage(T+ "§f" + p.getName() + ": " + e.message());
        }
    }

    /* ===============================
                초대 시스템
       =============================== */

    private void invitePlayer(Player inviter, String name) {
        Player target = Bukkit.getPlayer(name);
        if (target == null) {
            inviter.sendMessage(T+"§c플레이어를 찾을 수 없습니다.");
            return;
        }

        // 자기 자신에게 초대하지 못하도록
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            inviter.sendMessage(T+"§c자기 자신을 초대할 수 없습니다.");
            return;
        }

        // 대상이 이미 팀에 속해있는지 확인
        if (getPlayerTeam(target) != null) {
            inviter.sendMessage(T+"§c이미 팀에 가입된 플레이어입니다.");
            return;
        }

        org.bukkit.scoreboard.Team team = getPlayerTeam(inviter);
        if (team == null) {
            inviter.sendMessage(T+"§c당신은 팀에 속해있지 않습니다.");
            return;
        }

        // 초대 쿨다운 확인 (초대한사람:대상 단위)
        String cooldownKey = inviter.getUniqueId().toString() + ":" + target.getUniqueId().toString();
        long now = System.currentTimeMillis();
        Long last = inviteCooldowns.get(cooldownKey);
        if (last != null && now - last < INVITE_COOLDOWN_MS) {
            long remain = (INVITE_COOLDOWN_MS - (now - last)) / 1000;
            inviter.sendMessage(T + "§c해당 플레이어에게는 잠시 후에 다시 초대할 수 있습니다. 남은 시간: " + remain + "초");
            return;
        }

        inviteMap.put(target.getUniqueId(), team.getName());

        inviter.sendMessage(T+name + "님에게 팀 가입 초대를 보냈습니다.");
        target.sendMessage(T+inviter.getName() + "님이 팀 가입 초대를 보냈습니다.");

        TextComponent accept = new TextComponent("[수락]");
        accept.setColor(ChatColor.GREEN);
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/팀 수락 " + team.getName()));

        TextComponent deny = new TextComponent("[거절]");
        deny.setColor(ChatColor.RED);
        deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/팀 거절 " + team.getName()));

        target.spigot().sendMessage(accept, new TextComponent(" "), deny);

        // 초대 알림: 초대한 팀의 팀원들에게만 보이도록 전송
        broadcast(team, inviter.getName() + "님이 " + target.getName() + "님을 초대했습니다.");

        // 쿨다운 기록
        inviteCooldowns.put(cooldownKey, now);

        saveTeamData();
    }

    /* ===============================
                팀 탈퇴
       =============================== */

    private void leaveTeam(Player p) {
        org.bukkit.scoreboard.Team team = getPlayerTeam(p);
        team.removeEntry(p.getName());
        broadcast(team, T+p.getName() + "님이 팀을 탈퇴하였습니다.");
        saveTeamData();
    }

    /* ===============================
                추방
       =============================== */

    private void kickPlayer(Player executor, String name) {
        Player target = Bukkit.getPlayer(name);
        if (target == null) {
            executor.sendMessage(T+"§c플레이어를 찾을 수 없습니다.");
            return;
        }

        org.bukkit.scoreboard.Team team = getPlayerTeam(executor);
        if (team == null) {
            executor.sendMessage(T+"§c당신은 팀에 속해있지 않습니다.");
            return;
        }

        // 대상이 같은 팀에 속해 있는지 확인
        if (!team.hasEntry(target.getName())) {
            executor.sendMessage(T+"§c해당 플레이어는 당신의 팀에 없습니다.");
            return;
        }

        // 본인 추방 방지
        if (executor.getUniqueId().equals(target.getUniqueId())) {
            executor.sendMessage(T+"§c자기 자신을 추방할 수 없습니다.");
            return;
        }

        // 대상이 팀장인지 확인하여 팀장 추방 금지
        UUID targetId = target.getUniqueId();
        String targetRole = roleMap.get(targetId);
        if ("LEADER".equals(targetRole)) {
            executor.sendMessage(T+"§c팀장은 추방할 수 없습니다.");
            return;
        }

        team.removeEntry(name);

        broadcast(team, T+executor.getName() + "님에 의해 " + name + "님이 팀에서 추방되었습니다.");
        target.sendMessage(T+"팀에서 추방되었습니다.");
        // 역할 제거
        roleMap.remove(targetId);
        saveTeamData();
    }

    /* ===============================
                PVP
       =============================== */

    private void togglePvp(Player p) {
        org.bukkit.scoreboard.Team team = getPlayerTeam(p);
        if (team == null) {
            p.sendMessage(T+"§c팀에 속해있지 않습니다.");
            return;
        }
        boolean mode = teamPvp.getOrDefault(team.getName(), false);
        teamPvp.put(team.getName(), !mode);
        broadcast(team, !mode ? "팀 pvp ON" : "팀 pvp OFF");
        saveTeamData();
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;

        Player a = (Player) e.getDamager();
        Player b = (Player) e.getEntity();

        org.bukkit.scoreboard.Team ta = getPlayerTeam(a);
        org.bukkit.scoreboard.Team tb = getPlayerTeam(b);

        if (ta == null || tb == null) return;
        if (!ta.getName().equals(tb.getName())) return;

        if (!teamPvp.getOrDefault(ta.getName(), false))
            e.setCancelled(true);
    }

    /* ===============================
                해산
       =============================== */

    private void disbandTeam(Player leader) {
        org.bukkit.scoreboard.Team team = getPlayerTeam(leader);

        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayer(entry);
            if (member != null)
                member.sendMessage(T+"팀이 해산되었습니다.");
        }

        team.unregister();
        saveTeamData();
    }

    private void createTeam(Player p, String teamName) {
        if (getPlayerTeam(p) != null) {
            p.sendMessage(T+"§c이미 팀에 소속되어 있습니다.");
            return;
        }
        if (scoreboard.getTeam(teamName) != null) {
            p.sendMessage(T+"§c이미 존재하는 팀 이름입니다.");
            return;
        }

        org.bukkit.scoreboard.Team team = scoreboard.registerNewTeam(teamName);
        team.addEntry(p.getName());
        roleMap.put(p.getUniqueId(), "LEADER");
        p.sendMessage(T+"§a" + teamName + " 팀이 생성되었습니다! (등급: 팀장)");
        saveTeamData();
    }

    // 부팀장 임명
    private void promoteToSub(Player leader, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            leader.sendMessage(T+"§c플레이어를 찾을 수 없습니다.");
            return;
        }

        org.bukkit.scoreboard.Team team = getPlayerTeam(leader);
        if (team == null || !team.hasEntry(target.getName())) {
            leader.sendMessage(T+"§c해당 플레이어는 팀원이 아닙니다.");
            return;
        }

        UUID targetId = target.getUniqueId();
        String currentRole = roleMap.get(targetId);
        if ("SUB".equals(currentRole)) {
            leader.sendMessage(T+"§c해당 플레이어는 이미 부팀장입니다.");
            return;
        }

        // Count current SUBs in this team
        int subCount = 0;
        for (String entry : team.getEntries()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(entry);
            UUID id = op.getUniqueId();
            if ("SUB".equals(roleMap.get(id))) subCount++;
        }

        if (subCount >= 2) {
            leader.sendMessage(T+"§c부팀장은 최대 2명까지 가능합니다.");
            return;
        }

        roleMap.put(targetId, "SUB");
        broadcast(team, target.getName() + "님이 부팀장으로 임명되었습니다.");
        saveTeamData();
    }

    // 강등 (부팀장 -> 일반 팀원)
    private void demoteToMember(Player leader, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) return;

        org.bukkit.scoreboard.Team team = getPlayerTeam(leader);
        if (team == null || !team.hasEntry(target.getName())) return;

        UUID targetId = target.getUniqueId();
        String currentRole = roleMap.get(targetId);
        // 부팀장인 경우에만 강등 가능
        if (!"SUB".equals(currentRole)) {
            leader.sendMessage(T + "§c해당 플레이어는 부팀장이 아니어서 강등할 수 없습니다.");
            return;
        }

        roleMap.remove(targetId); // 또는 "MEMBER"로 설정
        broadcast(team, target.getName() + "님이 일반 팀원으로 강등되었습니다.");
        saveTeamData();
    }

    // 팀 수락 로직 가상 예시 (기존 코드에 추가 필요)
    private void acceptInvite(Player p, String teamName) {
        String invitedTeam = inviteMap.get(p.getUniqueId());
        if (invitedTeam == null || !invitedTeam.equals(teamName)) {
            p.sendMessage(T+"§c해당 팀의 초대가 없습니다.");
            return;
        }
        org.bukkit.scoreboard.Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.addEntry(p.getName());
            inviteMap.remove(p.getUniqueId());
            // 역할을 명시적으로 MEMBER로 설정
            roleMap.put(p.getUniqueId(), "MEMBER");
            p.sendMessage(T+"§a팀에 합류했습니다.");
            // 팀원들에게도 알림 (broadcast가 T를 붙이므로 여기서는 메시지만 전달)
            broadcast(team, p.getName() + "님이 팀에 합류했습니다.");
            saveTeamData();
        } else {
            p.sendMessage(T+"§c초대한 팀을 찾을 수 없습니다.");
            inviteMap.remove(p.getUniqueId());
            saveTeamData();
        }
    }

    /* ===============================
                권한 체크
       =============================== */

    private boolean canInvite(Player p) {
        String role = roleMap.get(p.getUniqueId());
        return role != null && (role.equals("LEADER") || role.equals("SUB"));
    }

    private boolean canKick(Player p) {
        return canInvite(p);
    }

    private boolean isLeader(Player p) {
        return "LEADER".equals(roleMap.get(p.getUniqueId()));
    }

    private org.bukkit.scoreboard.Team getPlayerTeam(Player p) {
        return scoreboard.getEntryTeam(p.getName());
    }

    private void broadcast(org.bukkit.scoreboard.Team team, String msg) {
        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayer(entry);
            if (member != null)
                member.sendMessage(T+"§f" + msg);
        }
    }

    // 팀 인원 목록 출력: 역할, 닉네임, 온라인/오프라인
    private void showTeamMembers(Player requester, org.bukkit.scoreboard.Team team) {
        requester.sendMessage(T+"§6=== 팀 인원 목록: " + team.getName() + " ===");

        // Collect members and send one line per member
        for (String entry : team.getEntries()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(entry);
            UUID id = op.getUniqueId();
            String role = roleMap.getOrDefault(id, "MEMBER");
            boolean online = op.isOnline();

            String roleDisplay;
            switch (role) {
                case "LEADER":
                    roleDisplay = "팀장";
                    break;
                case "SUB":
                    roleDisplay = "부팀장";
                    break;
                default:
                    roleDisplay = "팀원";
            }

            String status = online ? "온라인" : "오프라인";
            String color = online ? "§a" : "§7";

            requester.sendMessage(color + "- [" + roleDisplay + "] " + entry + " - " + status);
        }
    }

    /* ===============================
                자동완성
       =============================== */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

        if (args.length == 1)
            return Arrays.asList("인원목록", "채팅", "초대", "탈퇴", "추방", "pvp", "해산", "생성", "부팀장", "강등", "수락", "거절");

        if (args.length == 2 && (args[0].equals("초대") || args[0].equals("추방") || args[0].equals("강등") || args[0].equals("부팀장"))) {
            List<String> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers())
                list.add(p.getName());
            return list;
        }

        return Collections.emptyList();
    }

    /* ===============================
                파일 저장/로드
       =============================== */

    private void saveTeamData() {
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);

            // teams
            for (org.bukkit.scoreboard.Team t : scoreboard.getTeams()) {
                String path = "teams." + t.getName();
                cfg.set(path + ".members", new ArrayList<>(t.getEntries()));
                cfg.set(path + ".pvp", teamPvp.getOrDefault(t.getName(), false));
            }

            // roles
            cfg.set("roles", null);
            for (Map.Entry<UUID, String> e : roleMap.entrySet()) {
                cfg.set("roles." + e.getKey().toString(), e.getValue());
            }

            // teamChatMode
            cfg.set("teamChatMode", null);
            for (Map.Entry<UUID, Boolean> e : teamChatMode.entrySet()) {
                cfg.set("teamChatMode." + e.getKey().toString(), e.getValue());
            }

            // invites
            cfg.set("invites", null);
            for (Map.Entry<UUID, String> e : inviteMap.entrySet()) {
                cfg.set("invites." + e.getKey().toString(), e.getValue());
            }

            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Failed to save teaminfo.yml: " + ex.getMessage());
        }
    }

    private void loadTeamData() {
        if (!dataFile.exists()) return;

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);

        // teams
        if (cfg.isConfigurationSection("teams")) {
            for (String teamName : cfg.getConfigurationSection("teams").getKeys(false)) {
                String base = "teams." + teamName;
                List<String> members = cfg.getStringList(base + ".members");
                boolean pvp = cfg.getBoolean(base + ".pvp", false);

                org.bukkit.scoreboard.Team t = scoreboard.getTeam(teamName);
                if (t == null) t = scoreboard.registerNewTeam(teamName);

                for (String entry : members) {
                    if (!t.hasEntry(entry)) t.addEntry(entry);
                }

                teamPvp.put(teamName, pvp);
            }
        }

        // roles
        if (cfg.isConfigurationSection("roles")) {
            for (String key : cfg.getConfigurationSection("roles").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    roleMap.put(id, cfg.getString("roles." + key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // teamChatMode
        if (cfg.isConfigurationSection("teamChatMode")) {
            for (String key : cfg.getConfigurationSection("teamChatMode").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    teamChatMode.put(id, cfg.getBoolean("teamChatMode." + key, false));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // invites
        if (cfg.isConfigurationSection("invites")) {
            for (String key : cfg.getConfigurationSection("invites").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    inviteMap.put(id, cfg.getString("invites." + key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }
}
