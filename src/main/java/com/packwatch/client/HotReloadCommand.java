package com.packwatch.client;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/**
 * {@code /pw reload} - force a full resource reload right now (same as F3+T). {@code /pw enable <folder>} -
 * enable {@code resourcepacks/<folder>/} (creating a pack.mcmeta if needed) and reload. Once enabled, a pack
 * stays watched automatically for as long as it stays enabled -- see {@link EnabledPackSync} -- so disabling
 * it from the vanilla Resource Packs screen is all it takes to stop watching it again, no separate "unwatch"
 * needed. The folder argument tab-completes against whatever's actually sitting in {@code resourcepacks/}.
 * Registered under the full name {@code packwatch}, with {@code pw} as a short alias -- both work identically.
 */
public class HotReloadCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "packwatch";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("pw");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/pw reload | /pw enable <resourcepacks-subfolder>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "reload", "enable");
        }

        if (args.length == 2 && args[0].equals("enable")) {
            return getListOfStringsMatchingLastWord(args, listResourcePackFolders());
        }

        return Collections.emptyList();
    }

    /** Only meaningful for a one-shot tab completion: see the javadoc on {@link #addTabCompletionOptions}. */
    private static String[] listResourcePackFolders() {
        File dir = Minecraft.getMinecraft()
            .getResourcePackRepository()
            .getDirResourcepacks();
        File[] children = dir.listFiles();
        if (children == null) return new String[0];

        List<String> names = new ArrayList<String>();
        for (File child : children) {
            if (child.isDirectory()) names.add(child.getName());
        }
        return names.toArray(new String[0]);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
            return;
        }

        if (args[0].equals("reload")) {
            TextureReloadTrigger.refresh();
            sender.addChatMessage(new ChatComponentText("Reloaded resources."));
            return;
        }

        if (args[0].equals("enable")) {
            if (args.length < 2) {
                sender.addChatMessage(new ChatComponentText("Usage: /pw enable <resourcepacks-subfolder>"));
                return;
            }

            // Folder names can contain spaces; the command dispatcher already split on every space, so
            // stitch args[1..] back together instead of only taking args[1].
            StringBuilder folderNameBuilder = new StringBuilder(args[1]);
            for (int i = 2; i < args.length; i++) {
                folderNameBuilder.append(' ')
                    .append(args[i]);
            }
            String folderName = folderNameBuilder.toString();
            String error = TextureReloadTrigger.enableAndReload(folderName);
            if (error != null) {
                sender.addChatMessage(new ChatComponentText(error));
                return;
            }

            sender.addChatMessage(new ChatComponentText("Enabled and now watching '" + folderName + "'"));
            return;
        }

        sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
    }
}
