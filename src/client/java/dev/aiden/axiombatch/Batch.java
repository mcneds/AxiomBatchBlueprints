package dev.aiden.axiombatch;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class Batch {
    private record Job(Path input, Path output, String name) {}

    private final Minecraft client;
    private final AxiomRuntime axiom = new AxiomRuntime();
    private final Deque<Job> jobs = new ArrayDeque<>();
    private final Set<String> reservedOutputs = new HashSet<>();
    private final Path outputRoot;
    private final Consumer<String> feedback;
    private final int total;

    private int done;
    private boolean running;
    private Job active;
    private Object clipboard;
    private Object region;
    private Object preview;
    private CompletableFuture<Object> imageFuture;

    Batch(
        Minecraft client,
        Path source,
        Path outputRoot,
        Consumer<String> feedback
    ) throws Exception {
        this.client = client;
        this.outputRoot = outputRoot;
        this.feedback = feedback;

        if (Files.isDirectory(source)) {
            collectDirectory(source);
        } else {
            addSelectedFiles(List.of(source));
        }

        total = jobs.size();
        running = total > 0;
        log("Ready: " + total + " schematic(s)");
    }

    Batch(
        Minecraft client,
        List<Path> selectedFiles,
        Path outputRoot,
        Consumer<String> feedback
    ) throws Exception {
        this.client = client;
        this.outputRoot = outputRoot;
        this.feedback = feedback;

        addSelectedFiles(selectedFiles);

        total = jobs.size();
        running = total > 0;
        log("Ready: " + total + " schematic(s)");
    }

    int total() {
        return total;
    }

    boolean running() {
        return running;
    }

    void tick() {
        try {
            if (imageFuture != null) {
                if (!imageFuture.isDone()) return;
                finishCurrent();
                return;
            }

            if (active == null) startNext();
        } catch (Throwable t) {
            t.printStackTrace();
            String message = "Batch failed: " + AxiomBatchBlueprintsClient.rootMessage(t);
            log("ERROR: " + AxiomBatchBlueprintsClient.rootMessage(t));
            feedback.accept(message);
            axiom.clearPreview(preview);
            running = false;
        }
    }

    void cancel() {
        running = false;

        if (imageFuture != null) imageFuture.cancel(false);
        axiom.clearPreview(preview);
        jobs.clear();
    }

    String status() {
        return "AxiomBatchBP: " + done + "/" + total +
            (running ? " running" : " stopped") +
            (active == null ? "" : "; " + active.input().getFileName());
    }

    private void startNext() throws Exception {
        if (jobs.isEmpty()) {
            running = false;
            return;
        }

        active = jobs.removeFirst();
        log("Rendering " + active.input());

        clipboard = load(active.input());
        region = axiom.region(clipboard);

        if (axiom.count(region) <= 0) {
            throw new IllegalStateException("Empty schematic: " + active.input());
        }

        preview = axiom.newPreview(region);
        axiom.render(preview);
        imageFuture = axiom.image(preview);
    }

    private void finishCurrent() throws Exception {
        Object image = imageFuture.join();
        imageFuture = null;

        try {
            save(image);
        } finally {
            axiom.closeImage(image);
        }

        done++;
        log("[" + done + "/" + total + "] saved " + active.output().getFileName());

        axiom.clearPreview(preview);
        active = null;
        clipboard = null;
        region = null;
        preview = null;

        if (jobs.isEmpty()) {
            running = false;
            String message = "Finished: " + done + " schematic(s) -> " + outputRoot;
            log("Complete -> " + outputRoot);
            feedback.accept(message);
        }
    }

    private void collectDirectory(Path inputRoot) throws Exception {
        try (Stream<Path> stream = Files.walk(inputRoot)) {
            stream
                .filter(Files::isRegularFile)
                .filter(AxiomBatchBlueprintsClient::isSchematic)
                .sorted(Comparator.comparing(Path::toString))
                .forEach(path -> {
                    Path relative = inputRoot.relativize(path);
                    Path requested = replaceExtension(
                        outputRoot.resolve(relative),
                        ".bp"
                    );
                    Path output = uniqueOutputPath(requested);

                    jobs.add(new Job(
                        path,
                        output,
                        displayName(relative)
                    ));
                });
        }
    }

    private void addSelectedFiles(List<Path> selectedFiles) {
        for (Path file : selectedFiles) {
            if (!Files.isRegularFile(file)
                || !AxiomBatchBlueprintsClient.isSchematic(file)) {
                continue;
            }

            String stem = AxiomBatchBlueprintsClient.stripSchem(
                file.getFileName().toString()
            );
            Path output = uniqueOutputPath(
                outputRoot.resolve(stem + ".bp")
            );

            jobs.add(new Job(
                file,
                output,
                title(stem.replace('_', ' '))
            ));
        }
    }

    private Path uniqueOutputPath(Path requested) {
        Path parent = requested.getParent();
        String name = requested.getFileName().toString();
        String stem = name.toLowerCase(Locale.ROOT).endsWith(".bp")
            ? name.substring(0, name.length() - 3)
            : name;

        Path candidate = requested;
        int suffix = 2;

        while (Files.exists(candidate) || !reserve(candidate)) {
            candidate = parent.resolve(stem + "_" + suffix++ + ".bp");
        }

        return candidate;
    }

    private boolean reserve(Path output) {
        String key = output.toAbsolutePath()
            .normalize()
            .toString()
            .toLowerCase(Locale.ROOT);
        return reservedOutputs.add(key);
    }

    private Object load(Path file) throws Exception {
        CompoundTag tag = NbtIo.readCompressed(
            file,
            NbtAccounter.unlimitedHeap()
        );

        while (tag.keySet().size() == 1) {
            String key = tag.keySet().iterator().next();
            CompoundTag inner = tag.getCompoundOrEmpty(key);

            if (inner.isEmpty()) break;
            tag = inner;
        }

        if (!tag.contains("Version")) {
            throw new IllegalArgumentException(
                "Not a Sponge schematic: " + file
            );
        }

        return axiom.load(tag);
    }

    private void save(Object image) throws Exception {
        int count = axiom.count(region);
        String author = client.player == null
            ? "Unknown"
            : client.player.getName().getString();

        Object header = axiom.header(active.name(), author, count);
        Files.createDirectories(active.output().getParent());

        try (OutputStream out = new BufferedOutputStream(
            Files.newOutputStream(
                active.output(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
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

    private static Path replaceExtension(Path path, String extension) {
        String stem = AxiomBatchBlueprintsClient.stripSchem(
            path.getFileName().toString()
        );
        return path.resolveSibling(stem + extension);
    }

    private static String displayName(Path relative) {
        List<String> parts = new ArrayList<>();

        if (relative.getParent() != null) {
            for (Path part : relative.getParent()) {
                parts.add(title(part.toString().replace('_', ' ')));
            }
        }

        parts.add(title(
            AxiomBatchBlueprintsClient
                .stripSchem(relative.getFileName().toString())
                .replace('_', ' ')
        ));

        return String.join(" - ", parts);
    }

    private static String title(String input) {
        StringBuilder output = new StringBuilder();

        for (String word : input.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!output.isEmpty()) output.append(' ');

            output.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) output.append(word.substring(1));
        }

        return output.toString();
    }

    private static void log(String text) {
        System.out.println("[AxiomBatchBP] " + text);
    }
}
