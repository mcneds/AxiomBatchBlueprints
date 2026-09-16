package dev.aiden.axiombatch;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class AxiomBatchBlueprintsClient implements ClientModInitializer {
    private static final float THUMBNAIL_YAW = 135.0F;
    private static final float THUMBNAIL_PITCH = 30.0F;

    private static Batch batch;
    private static boolean choosingFolders = false;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("axiombatchbp")
                // No arguments: use Axiom's native system folder picker twice.
                .executes(ctx -> chooseFolders(ctx.getSource()))

                // Explicit form:
                // /axiombatchbp "<source directory>" "<destination directory>"
                .then(ClientCommands.argument("source", StringArgumentType.string())
                    .then(ClientCommands.argument("destination", StringArgumentType.string())
                        .executes(ctx -> startBatch(
                            ctx.getSource(),
                            resolvePath(StringArgumentType.getString(ctx, "source")),
                            resolvePath(StringArgumentType.getString(ctx, "destination"))
                        ))
                    )
                )

                .then(ClientCommands.literal("status")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal(
                            batch == null ? "No batch started" : batch.status()
                        ));
                        return 1;
                    })
                )

                .then(ClientCommands.literal("cancel")
                    .executes(ctx -> {
                        if (batch != null) {
                            batch.cancel();
                        }
                        ctx.getSource().sendFeedback(Component.literal("Batch cancelled"));
                        return 1;
                    })
                )
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (batch != null && batch.running) {
                batch.tick();
            }
        });
    }

    private static int chooseFolders(FabricClientCommandSource source) {
        if (batch != null && batch.running) {
            source.sendError(Component.literal(
                "A batch is already running. Use /axiombatchbp status or /axiombatchbp cancel."
            ));
            return 0;
        }

        if (choosingFolders) {
            source.sendError(Component.literal("A folder picker is already open."));
            return 0;
        }

        choosingFolders = true;
        Minecraft client = Minecraft.getInstance();

        try {
            Axiom axiom = new Axiom();
            String defaultPath = blueprintRoot().toString();

            source.sendFeedback(Component.literal("Choose source schematic folder..."));

            axiom.openFolderDialog(defaultPath).thenAccept(sourcePath -> {
                // AsyncFileDialogs clears its internal "dialog open" state only
                // after completing the first future. Scheduling onto the client
                // thread ensures the second picker is opened afterward.
                client.submit(() -> {
                    if (sourcePath == null) {
                        choosingFolders = false;
                        source.sendFeedback(Component.literal("Folder selection cancelled."));
                        return;
                    }

                    source.sendFeedback(Component.literal("Choose destination blueprint folder..."));

                    try {
                        String destinationDefault = Path.of(sourcePath)
                            .toAbsolutePath()
                            .normalize()
                            .getParent() == null
                                ? defaultPath
                                : Path.of(sourcePath)
                                    .toAbsolutePath()
                                    .normalize()
                                    .getParent()
                                    .toString();

                        axiom.openFolderDialog(destinationDefault).thenAccept(destinationPath ->
                            client.submit(() -> {
                                choosingFolders = false;

                                if (destinationPath == null) {
                                    source.sendFeedback(Component.literal(
                                        "Folder selection cancelled."
                                    ));
                                    return;
                                }

                                startBatch(
                                    source,
                                    Path.of(sourcePath).toAbsolutePath().normalize(),
                                    Path.of(destinationPath).toAbsolutePath().normalize()
                                );
                            })
                        );
                    } catch (Throwable t) {
                        choosingFolders = false;
                        t.printStackTrace();
                        source.sendError(Component.literal(
                            "Could not open destination folder picker: " + rootMessage(t)
                        ));
                    }
                });
            });

            return 1;
        } catch (Throwable t) {
            choosingFolders = false;
            t.printStackTrace();
            source.sendError(Component.literal(
                "Could not open folder picker: " + rootMessage(t)
            ));
            return 0;
        }
    }

    private static int startBatch(
        FabricClientCommandSource source,
        Path input,
        Path output
    ) {
        if (batch != null && batch.running) {
            source.sendError(Component.literal(
                "A batch is already running. Use /axiombatchbp status or /axiombatchbp cancel."
            ));
            return 0;
        }

        if (!Files.isDirectory(input)) {
            source.sendError(Component.literal(
                "Source must be an existing directory: " + input
            ));
            return 0;
        }

        if (Files.exists(output) && !Files.isDirectory(output)) {
            source.sendError(Component.literal(
                "Destination must be a directory: " + output
            ));
            return 0;
        }

        try {
            Files.createDirectories(output);

            batch = new Batch(
                Minecraft.getInstance(),
                input,
                output
            );

            source.sendFeedback(Component.literal(
                "Started: " + batch.total + " schematic(s) -> " + output
            ));

            return 1;
        } catch (Throwable t) {
            t.printStackTrace();
            source.sendError(Component.literal(
                "Failed to start: " + rootMessage(t)
            ));
            return 0;
        }
    }

    private static Path blueprintRoot() {
        return FabricLoader.getInstance()
            .getGameDir()
            .resolve("config")
            .resolve("axiom")
            .resolve("blueprints")
            .toAbsolutePath()
            .normalize();
    }

    private static Path resolvePath(String raw) {
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : blueprintRoot().resolve(path))
            .toAbsolutePath()
            .normalize();
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null
            ? t.getClass().getSimpleName()
            : t.getMessage();
    }

    private static String stripSchem(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".schem")
            ? name.substring(0, name.length() - ".schem".length())
            : name;
    }

    private record Job(Path in, Path out, String name) {}

    /**
     * Runtime-only access to Axiom internals.
     *
     * Keeping Axiom types out of this class's signatures avoids a compile-time
     * dependency on AxiomClientAPI while still using Axiom's exact loader,
     * renderer, native file dialogs and blueprint writer at runtime.
     */
    private static final class Axiom {
        final Method loadSponge;
        final Method setRegion;
        final Method setYaw;
        final Method setPitch;
        final Method render;
        final Method toImage;
        final Method clear;
        final Method write;
        final Method openFolderDialog;

        final Constructor<?> previewCtor;
        final Constructor<?> headerCtor;

        Axiom() throws Exception {
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

            headerCtor = Arrays.stream(header.getConstructors())
                .filter(c -> c.getParameterCount() == 8 || c.getParameterCount() == 9)
                .findFirst()
                .orElseThrow();
        }

        private static Method method(
            Class<?> type,
            String name,
            int argumentCount,
            boolean isStatic
        ) throws Exception {
            for (Method m : type.getMethods()) {
                if (m.getName().equals(name)
                    && m.getParameterCount() == argumentCount
                    && Modifier.isStatic(m.getModifiers()) == isStatic) {
                    m.setAccessible(true);
                    return m;
                }
            }

            throw new NoSuchMethodException(
                type.getName() + "." + name + "/" + argumentCount
            );
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
            return Math.toIntExact(
                ((Number) noArgs(region, "count")).longValue()
            );
        }

        Object preview() throws Exception {
            return previewCtor.newInstance();
        }

        void configure(Object preview, Object region) throws Exception {
            setRegion.invoke(preview, region);
            setYaw.invoke(preview, THUMBNAIL_YAW, false);
            setPitch.invoke(preview, THUMBNAIL_PITCH, false);
        }

        void render(Object preview) throws Exception {
            render.invoke(preview, 960, false, false);
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Object> image(Object preview) throws Exception {
            return (CompletableFuture<Object>) toImage.invoke(
                preview,
                96,
                true
            );
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<String> openFolderDialog(String defaultPath)
            throws Exception {

            return (CompletableFuture<String>) openFolderDialog.invoke(
                null,
                defaultPath
            );
        }

        void clear(Object preview) {
            try {
                if (preview != null) {
                    clear.invoke(preview);
                }
            } catch (Throwable ignored) {
            }
        }

        void closeImage(Object image) {
            try {
                if (image instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            } catch (Throwable ignored) {
            }
        }

        Object header(
            String name,
            String author,
            int count
        ) throws Exception {
            // Stamp-oriented defaults:
            // - yaw/pitch match Axiom Create Blueprint defaults
            // - ContainsAir=false so empty schematic space does not carve terrain
            if (headerCtor.getParameterCount() == 8) {
                return headerCtor.newInstance(
                    name,
                    author,
                    List.of(),
                    THUMBNAIL_YAW,
                    THUMBNAIL_PITCH,
                    false,
                    count,
                    false
                );
            }

            return headerCtor.newInstance(
                2,
                name,
                author,
                List.of(),
                THUMBNAIL_YAW,
                THUMBNAIL_PITCH,
                false,
                count,
                false
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
            write.invoke(
                null,
                out,
                header,
                image,
                region,
                blockEntities,
                entities
            );
        }

        private static Object noArgs(
            Object target,
            String name
        ) throws Exception {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        }
    }

    private static final class Batch {
        final Minecraft client;
        final Axiom axiom = new Axiom();
        final Deque<Job> jobs = new ArrayDeque<>();

        final Path inputRoot;
        final Path outputRoot;
        final int total;

        int done = 0;
        boolean running = true;

        Job active;
        Object clipboard;
        Object region;
        Object preview;
        CompletableFuture<Object> future;

        Batch(
            Minecraft client,
            Path inputRoot,
            Path outputRoot
        ) throws Exception {
            this.client = client;
            this.inputRoot = inputRoot;
            this.outputRoot = outputRoot;

            collectJobs();

            this.total = jobs.size();

            if (total == 0) {
                running = false;
            }

            log("Ready: " + total + " schematic(s)");
        }

        void collectJobs() throws Exception {
            try (Stream<Path> stream = Files.walk(inputRoot)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".schem"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        Path relative = inputRoot.relativize(path);
                        Path destination = replaceExtension(
                            outputRoot.resolve(relative),
                            ".bp"
                        );

                        jobs.add(new Job(
                            path,
                            destination,
                            displayName(relative)
                        ));
                    });
            }
        }

        void tick() {
            try {
                if (future != null) {
                    if (!future.isDone()) {
                        return;
                    }

                    Object image = future.join();
                    future = null;

                    try {
                        save(image);
                    } finally {
                        axiom.closeImage(image);
                    }

                    done++;

                    log(
                        "[" + done + "/" + total + "] saved " +
                        active.out().getFileName()
                    );

                    axiom.clear(preview);

                    active = null;
                    clipboard = null;
                    region = null;
                    preview = null;

                    if (jobs.isEmpty()) {
                        running = false;
                        log("Complete -> " + outputRoot);
                    }

                    return;
                }

                if (active == null) {
                    if (jobs.isEmpty()) {
                        running = false;
                        return;
                    }

                    active = jobs.removeFirst();

                    log("Rendering " + active.in());

                    clipboard = load(active.in());
                    region = axiom.region(clipboard);

                    if (axiom.count(region) <= 0) {
                        throw new IllegalStateException("Empty schematic");
                    }

                    preview = axiom.preview();
                    axiom.configure(preview, region);
                    axiom.render(preview);
                    future = axiom.image(preview);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                log("ERROR: " + rootMessage(t));
                axiom.clear(preview);
                running = false;
            }
        }

        Object load(Path file) throws Exception {
            CompoundTag tag = NbtIo.readCompressed(
                file,
                NbtAccounter.unlimitedHeap()
            );

            while (tag.keySet().size() == 1) {
                Set<String> keys = tag.keySet();
                String key = keys.iterator().next();
                CompoundTag inner = tag.getCompoundOrEmpty(key);

                if (inner.isEmpty()) {
                    break;
                }

                tag = inner;
            }

            if (!tag.contains("Version")) {
                throw new IllegalArgumentException(
                    "Not a Sponge schematic: " + file
                );
            }

            return axiom.load(tag);
        }

        void save(Object image) throws Exception {
            int count = axiom.count(region);

            String author = client.player == null
                ? "Unknown"
                : client.player.getName().getString();

            Object header = axiom.header(
                active.name(),
                author,
                count
            );

            Files.createDirectories(
                active.out().getParent()
            );

            try (OutputStream out = new BufferedOutputStream(
                Files.newOutputStream(active.out())
            )) {
                axiom.write(
                    out,
                    header,
                    image,
                    region,
                    axiom.blockEntities(clipboard),
                    axiom.entities(clipboard)
                );
            }
        }

        void cancel() {
            running = false;

            if (future != null) {
                future.cancel(false);
            }

            axiom.clear(preview);
            jobs.clear();
        }

        String status() {
            return "AxiomBatchBP: " +
                done + "/" + total +
                (running ? " running" : " stopped") +
                (active == null
                    ? ""
                    : "; " + active.in().getFileName());
        }

        static Path replaceExtension(
            Path path,
            String extension
        ) {
            String name = path.getFileName().toString();
            String stem = stripSchem(name);

            return path.resolveSibling(
                stem + extension
            );
        }

        static String displayName(Path relative) {
            List<String> parts = new ArrayList<>();

            if (relative.getParent() != null) {
                for (Path part : relative.getParent()) {
                    parts.add(
                        title(
                            part.toString()
                                .replace('_', ' ')
                        )
                    );
                }
            }

            parts.add(
                title(
                    stripSchem(
                        relative.getFileName().toString()
                    ).replace('_', ' ')
                )
            );

            return String.join(" - ", parts);
        }

        static String title(String input) {
            StringBuilder out = new StringBuilder();

            for (String word : input.trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }

                if (!out.isEmpty()) {
                    out.append(' ');
                }

                out.append(
                    Character.toUpperCase(word.charAt(0))
                );

                if (word.length() > 1) {
                    out.append(word.substring(1));
                }
            }

            return out.toString();
        }

        static void log(String text) {
            System.out.println(
                "[AxiomBatchBP] " + text
            );
        }
    }
}
