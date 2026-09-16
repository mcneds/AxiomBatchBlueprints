package dev.aiden.axiombatch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class AxiomBatchBlueprintsClient implements ClientModInitializer {
    private static Batch batch;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("axiombatchbp")
                .executes(ctx -> {
                    if (batch != null && batch.running) {
                        ctx.getSource().sendFeedback(Component.literal(batch.status()));
                        return 0;
                    }
                    Path source = findSource();
                    if (source == null) {
                        ctx.getSource().sendError(Component.literal(
                            "Could not find tree_schems_for_schemconvert under config/axiom/blueprints"));
                        return 0;
                    }
                    try {
                        batch = new Batch(Minecraft.getInstance(), source, source.resolveSibling("NativeBP"));
                        ctx.getSource().sendFeedback(Component.literal("Started: " + batch.total + " schematics"));
                        return 1;
                    } catch (Throwable t) {
                        t.printStackTrace();
                        ctx.getSource().sendError(Component.literal("Failed to start: " + rootMessage(t)));
                        return 0;
                    }
                })
                .then(ClientCommands.literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(batch == null ? "No batch started" : batch.status()));
                    return 1;
                }))
                .then(ClientCommands.literal("cancel").executes(ctx -> {
                    if (batch != null) batch.cancel();
                    ctx.getSource().sendFeedback(Component.literal("Batch cancelled"));
                    return 1;
                }))
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (batch != null && batch.running) batch.tick();
        });
    }

    private static Path findSource() {
        Path root = FabricLoader.getInstance().getGameDir()
            .resolve("config").resolve("axiom").resolve("blueprints");
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> paths = Files.walk(root, 10)) {
            return paths.filter(Files::isDirectory)
                .filter(p -> p.getFileName() != null && p.getFileName().toString().equals("tree_schems_for_schemconvert"))
                .filter(p -> Files.isDirectory(p.resolve("dark")) && Files.isDirectory(p.resolve("pale_no_birch")))
                .findFirst().orElse(null);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
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

        private static Method method(Class<?> c, String name, int argc, boolean isStatic) throws Exception {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc &&
                    Modifier.isStatic(m.getModifiers()) == isStatic) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw new NoSuchMethodException(c.getName() + "." + name + "/" + argc);
        }

        Object load(CompoundTag tag) throws Exception { return loadSponge.invoke(null, tag); }
        Object region(Object clipboard) throws Exception { return noArgs(clipboard, "blockRegion"); }
        Object blockEntities(Object clipboard) throws Exception { return noArgs(clipboard, "blockEntities"); }
        Object entities(Object clipboard) throws Exception { return noArgs(clipboard, "entities"); }
        int count(Object region) throws Exception { return Math.toIntExact(((Number) noArgs(region, "count")).longValue()); }
        Object preview() throws Exception { return previewCtor.newInstance(); }
        void configure(Object p, Object r) throws Exception {
            setRegion.invoke(p, r);
            setYaw.invoke(p, 135.0F, false);
            setPitch.invoke(p, 30.0F, false);
        }
        void render(Object p) throws Exception { render.invoke(p, 960, false, false); }
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> image(Object p) throws Exception { return (CompletableFuture<Object>) toImage.invoke(p, 96, true); }
        void clear(Object p) { try { if (p != null) clear.invoke(p); } catch (Throwable ignored) {} }
        void closeImage(Object image) { try { if (image instanceof AutoCloseable c) c.close(); } catch (Throwable ignored) {} }

        Object header(String name, String author, int count) throws Exception {
            if (headerCtor.getParameterCount() == 8)
                return headerCtor.newInstance(name, author, List.of(), 135.0F, 30.0F, false, count, false);
            return headerCtor.newInstance(2, name, author, List.of(), 135.0F, 30.0F, false, count, false);
        }

        void write(OutputStream out, Object header, Object image, Object region, Object be, Object entities) throws Exception {
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
        final Path outputRoot;
        final int total;
        int done = 0;
        boolean running = true;
        Job active;
        Object clipboard, region, preview;
        CompletableFuture<Object> future;

        Batch(Minecraft client, Path source, Path output) throws Exception {
            this.client = client;
            this.outputRoot = output;
            addFamily(source.resolve("dark"), output.resolve("dark"), "dark", "Tree Dark");
            addFamily(source.resolve("pale_no_birch"), output.resolve("pale_no_birch"), "pale", "Tree Pale");
            this.total = jobs.size();
            if (total == 0) running = false;
            log("Ready: " + total + " schematics");
        }

        void addFamily(Path src, Path out, String prefix, String displayPrefix) throws Exception {
            Files.createDirectories(out);
            try (Stream<Path> s = Files.list(src)) {
                s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".schem"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String stem = p.getFileName().toString().replaceFirst("\\.schem$", "")
                            .replace("_pale_no_birch", "");
                        jobs.add(new Job(p, out.resolve(prefix + "_" + stem + ".bp"),
                            displayPrefix + " - " + title(stem.replace('_', ' '))));
                    });
            }
        }

        void tick() {
            try {
                if (future != null) {
                    if (!future.isDone()) return;
                    Object image = future.join();
                    future = null;
                    try { save(image); } finally { axiom.closeImage(image); }
                    done++;
                    log("[" + done + "/" + total + "] saved " + active.out().getFileName());
                    axiom.clear(preview);
                    active = null; clipboard = region = preview = null;
                    if (jobs.isEmpty()) { running = false; log("Complete -> " + outputRoot); }
                    return;
                }

                if (active == null) {
                    if (jobs.isEmpty()) { running = false; return; }
                    active = jobs.removeFirst();
                    log("Rendering " + active.in().getFileName());
                    clipboard = load(active.in());
                    region = axiom.region(clipboard);
                    if (axiom.count(region) <= 0) throw new IllegalStateException("Empty schematic");
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
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            while (tag.keySet().size() == 1) {
                String key = tag.keySet().iterator().next();
                CompoundTag inner = tag.getCompoundOrEmpty(key);
                if (inner.isEmpty()) break;
                tag = inner;
            }
            if (!tag.contains("Version")) throw new IllegalArgumentException("Not a Sponge schematic: " + file);
            return axiom.load(tag);
        }

        void save(Object image) throws Exception {
            int count = axiom.count(region);
            String author = client.player == null ? "Unknown" : client.player.getName().getString();
            Object header = axiom.header(active.name(), author, count);
            Files.createDirectories(active.out().getParent());
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(active.out()))) {
                axiom.write(out, header, image, region, axiom.blockEntities(clipboard), axiom.entities(clipboard));
            }
        }

        void cancel() {
            running = false;
            if (future != null) future.cancel(false);
            axiom.clear(preview);
            jobs.clear();
        }

        String status() {
            return "AxiomBatchBP: " + done + "/" + total + (running ? " running" : " stopped") +
                (active == null ? "" : "; " + active.in().getFileName());
        }

        static String title(String s) {
            StringBuilder out = new StringBuilder();
            for (String w : s.trim().split("\\s+")) {
                if (w.isEmpty()) continue;
                if (!out.isEmpty()) out.append(' ');
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            return out.toString();
        }

        static void log(String s) { System.out.println("[AxiomBatchBP] " + s); }
    }
}
