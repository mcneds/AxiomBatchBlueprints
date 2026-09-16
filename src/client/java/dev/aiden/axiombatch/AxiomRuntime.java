package dev.aiden.axiombatch;

import org.lwjgl.util.nfd.NativeFileDialog;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.nbt.CompoundTag;

final class AxiomRuntime {
    static final float THUMBNAIL_YAW = 135.0F;
    static final float THUMBNAIL_PITCH = 30.0F;

    private final Method loadSponge;
    private final Method setRegion;
    private final Method setYaw;
    private final Method setPitch;
    private final Method render;
    private final Method toImage;
    private final Method clear;
    private final Method write;
    private final Method openFolderDialog;
    private final Field dialogsInitialized;

    private final Constructor<?> previewCtor;
    private final Constructor<?> headerCtor;

    AxiomRuntime() throws Exception {
        Class<?> loader = Class.forName(
            "com.moulberry.axiom.editor.schematic.SchematicLoader"
        );
        Class<?> preview = Class.forName(
            "com.moulberry.axiom.editor.BlueprintPreview"
        );
        Class<?> header = Class.forName(
            "com.moulberry.axiom.blueprint.BlueprintHeader"
        );
        Class<?> io = Class.forName(
            "com.moulberry.axiom.blueprint.BlueprintIo"
        );
        Class<?> dialogs = Class.forName(
            "com.moulberry.axiom.utils.AsyncFileDialogs"
        );

        loadSponge = method(loader, "loadSponge", 1, true);
        previewCtor = preview.getConstructor();
        setRegion = method(preview, "setBlockRegion", 1, false);
        setYaw = method(preview, "setYaw", 2, false);
        setPitch = method(preview, "setPitch", 2, false);
        render = method(preview, "render", 3, false);
        toImage = method(preview, "toNativeImage", 2, false);
        clear = method(preview, "clear", 0, false);
        write = method(io, "write", 6, true);
        openFolderDialog = method(dialogs, "openFolderDialog", 1, true);

        dialogsInitialized = dialogs.getDeclaredField("initializedNfd");
        dialogsInitialized.setAccessible(true);

        headerCtor = Arrays.stream(header.getConstructors())
            .filter(c -> c.getParameterCount() == 8 || c.getParameterCount() == 9)
            .findFirst()
            .orElseThrow();
    }

    void ensureNativeFileDialogInitialized() throws Exception {
        synchronized (AxiomRuntime.class) {
            if (dialogsInitialized.getBoolean(null)) return;

            int result = NativeFileDialog.NFD_Init();
            if (result != NativeFileDialog.NFD_OKAY) {
                String error = NativeFileDialog.NFD_GetError();
                throw new IllegalStateException(
                    error == null ? "Could not initialize native file dialogs" : error
                );
            }

            dialogsInitialized.setBoolean(null, true);
        }
    }

    Object load(CompoundTag tag) throws Exception {
        return loadSponge.invoke(null, tag);
    }

    Object region(Object clipboard) throws Exception {
        return noArgs(clipboard, "blockRegion");
    }

    Object blockEntities(Object clipboard) throws Exception {
        return noArgs(clipboard, "blockEntities");
    }

    Object entities(Object clipboard) throws Exception {
        return noArgs(clipboard, "entities");
    }

    int count(Object region) throws Exception {
        return Math.toIntExact(((Number) noArgs(region, "count")).longValue());
    }

    Object newPreview(Object region) throws Exception {
        Object preview = previewCtor.newInstance();
        setRegion.invoke(preview, region);
        setYaw.invoke(preview, THUMBNAIL_YAW, false);
        setPitch.invoke(preview, THUMBNAIL_PITCH, false);
        return preview;
    }

    void render(Object preview) throws Exception {
        render.invoke(preview, 960, false, false);
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<Object> image(Object preview) throws Exception {
        return (CompletableFuture<Object>) toImage.invoke(preview, 96, true);
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<String> openFolderDialog(String defaultPath) throws Exception {
        return (CompletableFuture<String>) openFolderDialog.invoke(null, defaultPath);
    }

    void clearPreview(Object preview) {
        try {
            if (preview != null) clear.invoke(preview);
        } catch (Throwable ignored) {
        }
    }

    void closeImage(Object image) {
        try {
            if (image instanceof AutoCloseable closeable) closeable.close();
        } catch (Throwable ignored) {
        }
    }

    Object header(String name, String author, int count) throws Exception {
        if (headerCtor.getParameterCount() == 8) {
            return headerCtor.newInstance(
                name, author, List.of(),
                THUMBNAIL_YAW, THUMBNAIL_PITCH,
                false, count, false
            );
        }

        return headerCtor.newInstance(
            2, name, author, List.of(),
            THUMBNAIL_YAW, THUMBNAIL_PITCH,
            false, count, false
        );
    }

    void write(
        OutputStream out,
        Object header,
        Object image,
        Object region,
        Object blockEntities,
        Object entities
    ) throws Exception {
        write.invoke(null, out, header, image, region, blockEntities, entities);
    }

    private static Method method(
        Class<?> type,
        String name,
        int argumentCount,
        boolean isStatic
    ) throws Exception {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                && method.getParameterCount() == argumentCount
                && Modifier.isStatic(method.getModifiers()) == isStatic) {
                method.setAccessible(true);
                return method;
            }
        }

        throw new NoSuchMethodException(
            type.getName() + "." + name + "/" + argumentCount
        );
    }

    private static Object noArgs(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
