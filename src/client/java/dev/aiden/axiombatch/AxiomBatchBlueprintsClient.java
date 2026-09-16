package dev.aiden.axiombatch;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class AxiomBatchBlueprintsClient implements ClientModInitializer {
    private static final String CONFIG_FILE = "axiom-batch-blueprints.properties";
    private static Batch batch;
    private static Settings settings;

    @Override
    public void onInitializeClient() {
        settings = Settings.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("axiombatchbp")
                .executes(ctx -> {
                    sendHelp(ctx.getSource());
                    return 1;
                })
                .then(ClientCommands.literal("run")
                    .then(ClientCommands.argument("input", StringArgumentType.string())
                        .executes(ctx -> startBatch(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "input"),
                            null
                        ))
                        .then(ClientCommands.argument("output", StringArgumentType.string())
                            .executes(ctx -> startBatch(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "input"),
                                StringArgumentType.getString(ctx, "output")
                            ))
                        )
                    )
                )
                .then(ClientCommands.literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(
                        batch == null ? "No batch started" : batch.status()
                    ));
                    return 1;
                }))
                .then(ClientCommands.literal("cancel").executes(ctx -> {
                    if (batch != null) {
                        batch.cancel();
                    }
                    ctx.getSource().sendFeedback(Component.literal("Batch cancelled"));
                    return 1;
                }))
                .then(ClientCommands.literal("config")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal(settings.summary()));
                        return 1;
                    })
                    .then(ClientCommands.literal("yaw")
                        .then(ClientCommands.argument("value", FloatArgumentType.floatArg(-180.0F, 180.0F))
                            .executes(ctx -> {
                                settings.yaw = FloatArgumentType.getFloat(ctx, "value");
                                return saveSettings(ctx.getSource(), "yaw=" + settings.yaw);
                            })
                        )
                    )
                    .then(ClientCommands.literal("pitch")
                        .then(ClientCommands.argument("value", FloatArgumentType.floatArg(-90.0F, 90.0F))
                            .executes(ctx -> {
                                settings.pitch = FloatArgumentType.getFloat(ctx, "value");
                                return saveSettings(ctx.getSource(), "pitch=" + settings.pitch);
                            })
                        )
                    )
                    .then(ClientCommands.literal("containsAir")
                        .then(ClientCommands.argument("value", BoolArgumentType.bool())
                            .executes(ctx -> {
                                settings.containsAir = BoolArgumentType.getBool(ctx, "value");
                                return saveSettings(ctx.getSource(), "containsAir=" + settings.containsAir);
                            })
                        )
                    )
                    .then(ClientCommands.literal("overwrite")
                        .then(ClientCommands.argument("value", BoolArgumentType.bool())
                            .executes(ctx -> {
                                settings.overwrite = BoolArgumentType.getBool(ctx, "value");
                                return saveSettings(ctx.getSource(), "overwrite=" + settings.overwrite);
                            })
                        )
                    )
                    .then(ClientCommands.literal("recursive")
                        .then(ClientCommands.argument("value", BoolArgumentType.bool())
                            .executes(ctx -> {
                                settings.recursive = BoolArgumentType.getBool(ctx, "value");
                                return saveSettings(ctx.getSource(), "recursive=" + settings.recursive);
                            })
                        )
                    )
                    .then(ClientCommands.literal("folderNames")
                        .then(ClientCommands.argument("value", BoolArgumentType.bool())
                            .executes(ctx -> {
                                settings.folderNames = BoolArgumentType.getBool(ctx, "value");
                                return saveSettings(ctx.getSource(), "folderNames=" + settings.folderNames);
                            })
                        )
                    )
                    .then(ClientCommands.literal("reset")
                        .executes(ctx -> {
                            settings = new Settings();
                            settings.save();
                            ctx.getSource().sendFeedback(Component.literal(
                                "Reset AxiomBatchBP settings: " + settings.summary()
                            ));
                            return 1;
                        })
                    )
                )
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (batch != null && batch.running) {
                batch.tick();
            }
        });
    }

    private static int startBatch(
        FabricClientCommandSource source,
        String inputRaw,
        String outputRaw
    ) {
        if (batch != null && batch.running) {
            source.sendError(Component.literal(
                "A batch is already running. Use /axiombatchbp status or cancel."
            ));
            return 0;
        }

        Path blueprintRoot = blueprintRoot();
        Path input = resolvePath(blueprintRoot, inputRaw);

        if (!Files.exists(input)) {
            source.sendError(Component.literal("Input does not exist: " + input));
            return 0;
        }

        Path output;
        if (outputRaw != null) {
            output = resolvePath(blueprintRoot, outputRaw);
        } else if (Files.isDirectory(input)) {
            output = input.resolveSibling(input.getFileName().toString() + "_bp");
        } else {
            String stem = stripSchem(input.getFileName().toString());
            output = input.resolveSibling(stem + ".bp");
        }

        try {
            batch = new Batch(
                Minecraft.getInstance(),
                input,
                output,
                settings.copy()
            );
            source.sendFeedback(Component.literal(
                "Started: " + batch.total + " schematic(s), " +
                batch.skipped + " skipped -> " + output
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

    private static int saveSettings(FabricClientCommandSource source, String changed) {
        try {
            settings.save();
            source.sendFeedback(Component.literal(
                "Set " + changed + " | " + settings.summary()
            ));
            return 1;
        } catch (Throwable t) {
            t.printStackTrace();
            source.sendError(Component.literal(
                "Could not save settings: " + rootMessage(t)
            ));
            return 0;
        }
    }

    private static void sendHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(
            "AxiomBatchBP commands:\n" +
            "/axiombatchbp run \"<input>\" [\"<output>\"]\n" +
            "/axiombatchbp status | cancel\n" +
            "/axiombatchbp config\n" +
            "/axiombatchbp config yaw|pitch <number>\n" +
            "/axiombatchbp config containsAir|overwrite|recursive|folderNames <true|false>\n" +
            "Relative paths are under config/axiom/blueprints."
        ));
    }

    private static Path blueprintRoot() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("config")
            .resolve("axiom")
            .resolve("blueprints")
            .toAbsolutePath()
            .normalize();
    }

    private static Path resolvePath(Path blueprintRoot, String raw) {
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : blueprintRoot.resolve(path))
            .toAbsolutePath()
            .normalize();
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String stripSchem(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".schem")
            ? name.substring(0, name.length() - ".schem".length())
            : name;
    }

    private static final class Settings {
        float yaw = 135.0F;
        float pitch = 30.0F;
        boolean containsAir = false;
        boolean overwrite = true;
        boolean recursive = true;
        boolean folderNames = true;

        static Settings load() {
            Settings out = new Settings();
            Path file = configPath();

            if (!Files.isRegularFile(file)) {
                return out;
            }

            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                out.yaw = parseFloat(p.getProperty("yaw"), out.yaw);
                out.pitch = parseFloat(p.getProperty("pitch"), out.pitch);
                out.containsAir = parseBool(p.getProperty("containsAir"), out.containsAir);
                out.overwrite = parseBool(p.getProperty("overwrite"), out.overwrite);
                out.recursive = parseBool(p.getProperty("recursive"), out.recursive);
                out.folderNames = parseBool(p.getProperty("folderNames"), out.folderNames);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            return out;
        }

        void save() {
            Properties p = new Properties();
            p.setProperty("yaw", Float.toString(yaw));
            p.setProperty("pitch", Float.toString(pitch));
            p.setProperty("containsAir", Boolean.toString(containsAir));
            p.setProperty("overwrite", Boolean.toString(overwrite));
            p.setProperty("recursive", Boolean.toString(recursive));
            p.setProperty("folderNames", Boolean.toString(folderNames));

            Path file = configPath();
            try {
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) {
                    p.store(out, "AxiomBatchBlueprints settings");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Settings copy() {
            Settings out = new Settings();
            out.yaw = yaw;
            out.pitch = pitch;
            out.containsAir = containsAir;
            out.overwrite = overwrite;
            out.recursive = recursive;
            out.folderNames = folderNames;
            return out;
        }

        String summary() {
            return "yaw=" + yaw +
                ", pitch=" + pitch +
                ", containsAir=" + containsAir +
                ", overwrite=" + overwrite +
                ", recursive=" + recursive +
                ", folderNames=" + folderNames;
        }

        private static float parseFloat(String raw, float fallback) {
            if (raw == null) return fallback;
            try {
                return Float.parseFloat(raw);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static boolean parseBool(String raw, boolean fallback) {
            return raw == null ? fallback : Boolean.parseBoolean(raw);
        }

        private static Path configPath() {
            return FabricLoader.getInstance().getConfigDir()
                .resolve(CONFIG_FILE);
        }
    }

    private record Job(Path in, Path out, String name) {}

    /** Runtime-only access to Axiom internals, avoiding compile-time AxiomClientAPI dependencies. */
    private static final class Axiom {
        final Method loadSponge, setRegion, setYaw, setPitch, render, toImage, clear, write;
        final Constructor<?> previewCtor, headerCtor;

        Axiom() throws Exception {
            Class<?> loader = Class.forName("com.moulberry.axiom.editor.schematic.SchematicLoader");
            Class<?> preview = Class.forName("com.moulberry.axiom.editor.BlueprintPreview");
            Class<?> header = Class.forName("com.moulberry.axiom.blueprint.BlueprintHeader");
            Class<?> io = Class.forName("com.moulberry.axiom.blueprint.BlueprintIo");

            loadSponge = method(loader, "loadSponge", 1, true);
            previewCtor = preview.getConstructor();
            setRegion = method(preview, "setBlockRegion", 1, false);
            setYaw = method(preview, "setYaw", 2, false);
            setPitch = method(preview, "setPitch", 2, false);
            render = method(preview, "render", 3, false);
            toImage = method(preview, "toNativeImage", 2, false);
            clear = method(preview, "clear", 0, false);
            write = method(io, "write", 6, true);
            headerCtor = Arrays.stream(header.getConstructors())
                .filter(c -> c.getParameterCount() == 8 || c.getParameterCount() == 9)
                .findFirst().orElseThrow();
        }

        private static Method method(Class<?> c, String name, int argc, boolean isStatic)
            throws Exception {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) &&
                    m.getParameterCount() == argc &&
                    Modifier.isStatic(m.getModifiers()) == isStatic) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw new NoSuchMethodException(c.getName() + "." + name + "/" + argc);
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

        Object preview() throws Exception {
            return previewCtor.newInstance();
        }

        void configure(Object p, Object r, Settings s) throws Exception {
            setRegion.invoke(p, r);
            setYaw.invoke(p, s.yaw, false);
            setPitch.invoke(p, s.pitch, false);
        }

        void render(Object p) throws Exception {
            render.invoke(p, 960, false, false);
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Object> image(Object p) throws Exception {
            return (CompletableFuture<Object>) toImage.invoke(p, 96, true);
        }

        void clear(Object p) {
            try {
                if (p != null) {
                    clear.invoke(p);
                }
            } catch (Throwable ignored) {
            }
        }

        void closeImage(Object image) {
            try {
                if (image instanceof AutoCloseable c) {
                    c.close();
                }
            } catch (Throwable ignored) {
            }
        }

        Object header(String name, String author, int count, Settings s) throws Exception {
            if (headerCtor.getParameterCount() == 8) {
                return headerCtor.newInstance(
                    name, author, List.of(),
                    s.yaw, s.pitch, false, count, s.containsAir
                );
            }
            return headerCtor.newInstance(
                2, name, author, List.of(),
                s.yaw, s.pitch, false, count, s.containsAir
            );
        }

        void write(
            OutputStream out,
            Object header,
            Object image,
            Object region,
            Object be,
            Object entities
        ) throws Exception {
            write.invoke(null, out, header, image, region, be, entities);
        }

        private static Object noArgs(Object target, String name) throws Exception {
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
        final Settings settings;
        final int total;
        final int skipped;

        int done = 0;
        boolean running = true;
        Job active;
        Object clipboard, region, preview;
        CompletableFuture<Object> future;

        Batch(
            Minecraft client,
            Path input,
            Path output,
            Settings settings
        ) throws Exception {
            this.client = client;
            this.inputRoot = input;
            this.outputRoot = output;
            this.settings = settings;

            int[] skippedCounter = {0};
            collectJobs(input, output, skippedCounter);
            this.skipped = skippedCounter[0];
            this.total = jobs.size();

            if (total == 0) {
                running = false;
            }

            log("Ready: " + total + " schematic(s), " + skipped + " skipped");
        }

        void collectJobs(Path input, Path output, int[] skippedCounter) throws Exception {
            if (Files.isRegularFile(input)) {
                if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schem")) {
                    throw new IllegalArgumentException("Input file is not .schem: " + input);
                }

                Path out = output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bp")
                    ? output
                    : output.resolve(stripSchem(input.getFileName().toString()) + ".bp");

                addJob(input, out, Path.of(input.getFileName().toString()), skippedCounter);
                return;
            }

            if (!Files.isDirectory(input)) {
                throw new IllegalArgumentException("Input is not a directory or .schem file: " + input);
            }

            Stream<Path> stream = settings.recursive
                ? Files.walk(input)
                : Files.list(input);

            try (stream) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schem"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> {
                        try {
                            Path relative = input.relativize(p);
                            Path out = replaceExtension(output.resolve(relative), ".bp");
                            addJob(p, out, relative, skippedCounter);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }

        void addJob(
            Path input,
            Path output,
            Path relative,
            int[] skippedCounter
        ) throws Exception {
            if (Files.exists(output) && !settings.overwrite) {
                skippedCounter[0]++;
                return;
            }

            jobs.add(new Job(
                input,
                output,
                displayName(relative, settings.folderNames)
            ));
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
                    log("[" + done + "/" + total + "] saved " + active.out().getFileName());

                    axiom.clear(preview);
                    active = null;
                    clipboard = region = preview = null;

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
                    axiom.configure(preview, region, settings);
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
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

            while (tag.keySet().size() == 1) {
                String key = tag.keySet().iterator().next();
                CompoundTag inner = tag.getCompoundOrEmpty(key);
                if (inner.isEmpty()) {
                    break;
                }
                tag = inner;
            }

            if (!tag.contains("Version")) {
                throw new IllegalArgumentException("Not a Sponge schematic: " + file);
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
                count,
                settings
            );

            Files.createDirectories(active.out().getParent());

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
            return "AxiomBatchBP: " + done + "/" + total +
                (running ? " running" : " stopped") +
                ", skipped=" + skipped +
                (active == null ? "" : "; " + active.in().getFileName());
        }

        static Path replaceExtension(Path path, String extension) {
            String name = path.getFileName().toString();
            String stem = stripSchem(name);
            return path.resolveSibling(stem + extension);
        }

        static String displayName(Path relative, boolean folderNames) {
            List<String> parts = new ArrayList<>();

            if (folderNames && relative.getParent() != null) {
                for (Path p : relative.getParent()) {
                    parts.add(title(p.toString().replace('_', ' ')));
                }
            }

            parts.add(title(
                stripSchem(relative.getFileName().toString())
                    .replace('_', ' ')
            ));

            return String.join(" - ", parts);
        }

        static String title(String s) {
            StringBuilder out = new StringBuilder();

            for (String w : s.trim().split("\\s+")) {
                if (w.isEmpty()) {
                    continue;
                }

                if (!out.isEmpty()) {
                    out.append(' ');
                }

                out.append(Character.toUpperCase(w.charAt(0)))
                    .append(w.substring(1));
            }

            return out.toString();
        }

        static void log(String s) {
            System.out.println("[AxiomBatchBP] " + s);
        }
    }
}
