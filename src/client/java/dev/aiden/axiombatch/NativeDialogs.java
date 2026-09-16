package dev.aiden.axiombatch;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.lwjgl.util.nfd.NativeFileDialog;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NativeDialogs {
    private static final ExecutorService DIALOG_THREAD =
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "axiom-batch-file-dialog");
            thread.setDaemon(true);
            return thread;
        });

    private NativeDialogs() {
    }

    static CompletableFuture<List<Path>> openSchematicFiles(
        AxiomRuntime axiom,
        String defaultPath
    ) {
        CompletableFuture<List<Path>> future = new CompletableFuture<>();

        Runnable runnable = () -> {
            try {
                axiom.ensureNativeFileDialogInitialized();
                future.complete(runMultiFileDialog(defaultPath));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };

        if (Util.getPlatform() == Util.OS.OSX) {
            Minecraft.getInstance().submit(runnable);
        } else {
            DIALOG_THREAD.submit(runnable);
        }

        return future;
    }

    private static List<Path> runMultiFileDialog(String defaultPath) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer outPaths = stack.callocPointer(1);
            NFDFilterItem.Buffer filters = NFDFilterItem.malloc(1);

            try {
                filters.get(0)
                    .name(stack.UTF8("Schematic Files"))
                    .spec(stack.UTF8("schem"));

                int result = NativeFileDialog.NFD_OpenDialogMultiple(
                    outPaths,
                    filters,
                    defaultPath
                );

                if (result == NativeFileDialog.NFD_CANCEL) return List.of();
                if (result != NativeFileDialog.NFD_OKAY) {
                    throw nfdError("Native file dialog failed");
                }

                return readPathSet(stack, outPaths.get(0));
            } finally {
                filters.free();
            }
        }
    }

    private static List<Path> readPathSet(MemoryStack stack, long pathSet) {
        List<Path> selected = new ArrayList<>();

        try {
            IntBuffer countBuffer = stack.callocInt(1);
            int countResult = NativeFileDialog.NFD_PathSet_GetCount(
                pathSet,
                countBuffer
            );

            if (countResult != NativeFileDialog.NFD_OKAY) {
                throw nfdError("Could not read selection count");
            }

            int count = countBuffer.get(0);

            for (int i = 0; i < count; i++) {
                PointerBuffer pathOut = stack.callocPointer(1);
                int pathResult = NativeFileDialog.NFD_PathSet_GetPath(
                    pathSet,
                    i,
                    pathOut
                );

                if (pathResult != NativeFileDialog.NFD_OKAY) {
                    throw nfdError("Could not read selected path");
                }

                long pointer = pathOut.get(0);
                try {
                    Path path = Path.of(pathOut.getStringUTF8(0))
                        .toAbsolutePath()
                        .normalize();

                    if (Files.isRegularFile(path)
                        && AxiomBatchBlueprintsClient.isSchematic(path)) {
                        selected.add(path);
                    }
                } finally {
                    NativeFileDialog.NFD_PathSet_FreePath(pointer);
                }
            }

            return List.copyOf(selected);
        } finally {
            NativeFileDialog.NFD_PathSet_Free(pathSet);
        }
    }

    private static IllegalStateException nfdError(String fallback) {
        String error = NativeFileDialog.NFD_GetError();
        return new IllegalStateException(error == null ? fallback : error);
    }
}
