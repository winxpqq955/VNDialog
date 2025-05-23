package top.yourzi.dialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.yourzi.dialog.model.DialogEntry;
import top.yourzi.dialog.model.DialogSequence;
import top.yourzi.dialog.ui.DialogScreen;
import top.yourzi.dialog.network.NetworkHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Collections;

/**
 * 对话系统的核心管理类，负责加载和管理对话序列。
 */
public class DialogManager {
    private static final Gson GSON = new GsonBuilder().create();
    private static final DialogManager INSTANCE = new DialogManager();

    // 服务端: 存储所有从数据包加载的对话序列
    // 客户端: 存储从服务端同步过来的对话序列
    private final Map<String, DialogSequence> dialogSequences = new HashMap<>();
    // 当前显示的对话序列
    private DialogSequence currentSequence;
    // 当前显示的对话条目
    private DialogEntry currentEntry;
    // 对话历史记录
    private final List<DialogEntry> dialogHistory = new ArrayList<>();
    // 标记下一次对话推进是否由快速跳过触发
    private static boolean isFastForwardingNext = false;
    // 自动播放状态
    private static boolean isAutoPlaying = false;

    private DialogManager() {}
    
    /**
     * 向玩家发送消息。
     */
    @OnlyIn(Dist.CLIENT)
    private void sendPlayerMessage(Component message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(message);
        }
    }
    
    public static DialogManager getInstance() {
        return INSTANCE;
    }

    /**
     * 执行命令字符串。
     * 当对话条目或选项指定命令时，调用此方法。
     * @param command 要执行的命令字符串。
     */
    public void executeCommand(String command) {
        if (command != null && !command.isEmpty()) {
            Dialog.LOGGER.info("Client requesting server to execute command: {}", command);
            // 将命令发送到服务器执行
            NetworkHandler.sendExecuteCommandToServer(command);
        }
    }
    
    /**
     * 加载所有对话序列 (仅服务端调用)。
     * 此方法从数据包 (data/<modid>/dialogs/) 加载对话。
     * @param resourceManager 资源管理器实例。
     */
    public void loadDialogsFromServer(ResourceManager resourceManager) {
        dialogSequences.clear();
        Dialog.LOGGER.info("The server is loading the dialog file from the datapack......");

        Map<ResourceLocation, Resource> modSpecificResources = resourceManager.listResources("dialogs", resource -> resource.getPath().endsWith(".json")).entrySet().stream()
            .filter(entry -> entry.getKey().getNamespace().equals(Dialog.MODID))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Dialog.LOGGER.info("Find {} JSON file in the directory", modSpecificResources.size());

        modSpecificResources.forEach((resourceLocation, resource) -> {
            Dialog.LOGGER.info("Dialog files being processed. {}", resourceLocation);
            try {
                DialogSequence sequence = parseDialogSequenceFromFile(resource); // 解析对话序列
                if (sequence != null && sequence.getId() != null) {
                    dialogSequences.put(sequence.getId(), sequence);
                    Dialog.LOGGER.info("Successfully loaded dialog sequence. {}", sequence.getId());
                } else {
                    Dialog.LOGGER.warn("Empty dialog sequence or empty ID. {}", resourceLocation);
                }
            } catch (Exception e) {
                Dialog.LOGGER.error("Failed to load dialog file {}: {}", resourceLocation, e.getMessage(), e);
            }
        });

        Dialog.LOGGER.info("The server loaded a total of {} dialog sequences", dialogSequences.size());
        if (dialogSequences.isEmpty()) {
            Dialog.LOGGER.warn("No dialog sequence was found, please check the 'dialogs' directory ('data/{}/dialogs') or file format in the datapack.", Dialog.MODID);
        }
    }

    /**
     * 解析对话序列JSON文件 (内部使用, 服务端加载时调用)。
     * @param resource 资源文件。
     */
    private DialogSequence parseDialogSequenceFromFile(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.open(), StandardCharsets.UTF_8))) {
            return GSON.fromJson(reader, DialogSequence.class);
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            Dialog.LOGGER.error("Failure to read or parse dialog JSON file ({}): {}", resource.sourcePackId(), e.getMessage());
            // 尝试读取内容以进行更详细的调试
            try (BufferedReader contentReader = new BufferedReader(new InputStreamReader(resource.open(), StandardCharsets.UTF_8))) {
                StringBuilder jsonContent = new StringBuilder();
                String line;
                while ((line = contentReader.readLine()) != null) {
                    jsonContent.append(line);
                }
                Dialog.LOGGER.debug("JSON: {}", jsonContent.toString());
            } catch (IOException ioe) {
                Dialog.LOGGER.error("Unable to read problematic JSON content for debugging. {}", ioe.getMessage());
            }
            return null;
        }
    }

    /**
     * (客户端) 清空所有已缓存的对话数据。
     * 通常在从服务器断开或服务器重载数据包时调用。
     */
    @OnlyIn(Dist.CLIENT)
    public void clearAllDialogsOnClient() {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return;
        dialogSequences.clear();
        currentSequence = null;
        currentEntry = null;
        clearDialogHistory();
        Dialog.LOGGER.info("The client dialog cache has been cleared.");
    }

    /**
     * (客户端) 接收并缓存从服务器同步过来的所有对话数据。
     * @param dialogDataMap 一个映射，键是对话ID，值是对话内容的JSON字符串。
     */
    @OnlyIn(Dist.CLIENT)
    public void receiveAllDialogsFromServer(Map<String, String> dialogDataMap) {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return;
        clearAllDialogsOnClient(); // 先清空旧数据
        Dialog.LOGGER.info("The client receives {} conversation data for synchronization.", dialogDataMap.size());
        dialogDataMap.forEach((id, json) -> {
            try {
                DialogSequence sequence = GSON.fromJson(json, DialogSequence.class);
                if (sequence != null && sequence.getId() != null) {
                    if (!id.equals(sequence.getId())) {
                        Dialog.LOGGER.warn("Dialog ID mismatch! Expected ID: {}, ID in JSON: {}. Will use expected ID.", id, sequence.getId());
                    }
                    dialogSequences.put(id, sequence); // 使用map的key作为权威ID
                    Dialog.LOGGER.debug("Client Successfully Cached Conversation. {}", id);
                } else {
                    Dialog.LOGGER.warn("Parsing of the dialog data received from the server failed or the ID is null. ID: {}, JSON: {}", id, json);
                }
            } catch (com.google.gson.JsonSyntaxException e) {
                Dialog.LOGGER.error("Failed to parse the dialog JSON received from the server. ID: {}, 错误: {}", id, e.getMessage());
                Dialog.LOGGER.debug("(ID: {}): {}", id, json, e);
            }
        });
        Dialog.LOGGER.info("Client conversation data synchronization is complete, currently caching {} conversations.", dialogSequences.size());
        if (dialogSequences.isEmpty() && !dialogDataMap.isEmpty()) {
            Dialog.LOGGER.warn("Dialog data has been received but the cache is empty after parsing, please check the JSON format and content.");
        }
    }

    /**
     * 将对话条目添加到历史记录。
     */
    @OnlyIn(Dist.CLIENT)
    private void addDialogToHistory(DialogEntry entry) {
        if (entry != null) {
            dialogHistory.add(entry);
        }
    }

    /**
     * 获取对话历史记录。
     */
    @OnlyIn(Dist.CLIENT)
    public List<DialogEntry> getDialogHistory() {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return Collections.emptyList();
        return new ArrayList<>(dialogHistory);
    }

    /**
     * 清空对话历史记录。
     */
    @OnlyIn(Dist.CLIENT)
    private void clearDialogHistory() {
        dialogHistory.clear();
    }

    /**
     * 记录玩家在当前对话中选择的选项。
     * @param optionText 所选选项的文本。
     */
    @OnlyIn(Dist.CLIENT)
    public void recordChoiceForCurrentDialog(String optionText) {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return;
        if (currentEntry != null) {
            currentEntry.setSelectedOptionText(optionText);
            // 更新历史记录中最新的对应条目
            if (!dialogHistory.isEmpty()) {
                DialogEntry lastHistoryEntry = dialogHistory.get(dialogHistory.size() - 1);
                // 确保更新的是同一个对话条目（理论上应该是同一个）
                if (lastHistoryEntry == currentEntry) {
                    lastHistoryEntry.setSelectedOptionText(optionText);
                } else {
                    // 如果不是同一个条目，可能存在逻辑错误，或者 currentEntry 在添加到历史记录后被更改。
                    // 尝试通过ID查找并更新。
                    for (int i = dialogHistory.size() - 1; i >= 0; i--) {
                        if (dialogHistory.get(i).getId() != null && dialogHistory.get(i).getId().equals(currentEntry.getId())) {
                            dialogHistory.get(i).setSelectedOptionText(optionText);
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * (服务端) 获取所有对话序列的JSON表示，用于发送给客户端。
     */
    public Map<String, String> getAllDialogJsonsForSync() {
        Map<String, String> dialogJsons = new HashMap<>();
        dialogSequences.forEach((id, sequence) -> {
            dialogJsons.put(id, GSON.toJson(sequence));
        });
        return dialogJsons;
    }

    /**
     * 根据ID获取对话序列。
     * 服务端：从加载的对话中获取。
     * 客户端：从缓存的对话中获取。
     */
    public DialogSequence getDialogSequence(String id) {
        return dialogSequences.get(id);
    }
    
    /**
     * 获取所有对话序列。
     */
    public Map<String, DialogSequence> getAllDialogSequences() {
        return new HashMap<>(dialogSequences);
    }
    
    /**
     * 显示指定ID的对话序列。
     */
    @OnlyIn(Dist.CLIENT)
    public void receiveDialogData(String dialogId, String dialogJson) {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return;
        Dialog.LOGGER.info("Client receives dialog data: {}", dialogId);
        try {
            DialogSequence sequence = GSON.fromJson(dialogJson, DialogSequence.class);
            if (sequence != null && sequence.getId() != null) {
                dialogSequences.put(sequence.getId(), sequence);
                Dialog.LOGGER.info("Successfully parsing and storing conversations received from the server side: {}", sequence.getId());
                // 确保在主线程显示对话界面
                Minecraft.getInstance().execute(() -> showDialog(dialogId)); // Now show the dialog
            } else {
                Dialog.LOGGER.warn("Failed to parse the dialog data received from the server or the ID is null: {}", dialogId);
                sendPlayerMessage(Component.translatable("dialog.manager.received_sequence_empty", dialogId));
            }
        } catch (Exception e) {
            Dialog.LOGGER.error("Failed to parse dialog '{}' JSON received from server", dialogId);
            sendPlayerMessage(Component.translatable("dialog.manager.received_parse_failed", dialogId, e.getMessage()));
            e.printStackTrace();
        }
    }

    /**
     * 显示指定ID的对话序列。
     */
    @OnlyIn(Dist.CLIENT)
    public void showDialog(String dialogId) {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return;
        stopAutoPlay(); // 每次对话启动时重置自动播放为关闭状态
        DialogSequence sequence = getDialogSequence(dialogId);
        if (sequence == null) {
            Dialog.LOGGER.info("Dialog '{}' was not found locally and is being requested from the server...", dialogId);
            NetworkHandler.sendRequestDialogToServer(dialogId);
            sendPlayerMessage(Component.translatable("dialog.manager.requesting_from_server", dialogId));
            return;
        }
        
        clearDialogHistory(); // 开始新对话时清空历史记录
        currentSequence = sequence;
        currentEntry = sequence.getFirstEntry();
        addDialogToHistory(currentEntry); // 将第一个条目加入历史记录
        
        if (currentEntry == null) {
            Dialog.LOGGER.error("No entries found in dialog sequence: {}", dialogId);
            sendPlayerMessage(Component.translatable("dialog.manager.no_entries", dialogId));
            return;
        }
        
        // 显示对话界面
        Minecraft.getInstance().setScreen(new DialogScreen(currentSequence, currentEntry));
    }

    /**
     * 获取快速跳过标记。
     */
    public static boolean isFastForwardingNext() {
        return isFastForwardingNext;
    }

    /**
     * 设置快速跳过标记。
     */
    public static void setFastForwardingNext(boolean fastForwardingNext) {
        isFastForwardingNext = fastForwardingNext;
    }

    /**
     * 获取自动播放状态。
     */
    public static boolean isAutoPlaying() {
        return isAutoPlaying;
    }

    /**
     * 设置自动播放状态。
     */
    public static void setAutoPlaying(boolean autoPlaying) {
        isAutoPlaying = autoPlaying;
    }

    /**
     * 停止自动播放。
     */
    public static void stopAutoPlay() {
        isAutoPlaying = false;
    }
    
    /**
     * 显示对话序列中的下一条对话。
     */
    @OnlyIn(Dist.CLIENT)
    public void showNextDialog() {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return;
        if (currentSequence == null || currentEntry == null) {
            return;
        }
        
        DialogEntry nextEntry = currentSequence.getNextEntry(currentEntry);
        if (nextEntry == null) {
            // 对话结束，关闭对话界面
            Minecraft.getInstance().setScreen(null);
            currentSequence = null;
            currentEntry = null;
            return;
        }
        
        currentEntry = nextEntry;
        addDialogToHistory(currentEntry); // 将后续条目加入历史记录
        // 更新对话界面
        Minecraft.getInstance().setScreen(new DialogScreen(currentSequence, currentEntry));
    }
    /**
     * 根据选项跳转到指定的对话。
     */
    @OnlyIn(Dist.CLIENT)
    public void jumpToDialog(String targetId) {
        if (Minecraft.getInstance() == null || !Minecraft.getInstance().level.isClientSide) return;
        if (currentSequence == null) {
            return;
        }
        
        DialogEntry targetEntry = currentSequence.findEntryById(targetId);
        if (targetEntry == null) {
            Dialog.LOGGER.error("Target dialog entry not found: {}", targetId);
            sendPlayerMessage(Component.translatable("dialog.manager.target_not_found", targetId));
            return;
        }
        
        currentEntry = targetEntry;
        addDialogToHistory(currentEntry); // 将跳转的条目加入历史记录
        // 更新对话界面
        Minecraft.getInstance().setScreen(new DialogScreen(currentSequence, currentEntry));
    }
}