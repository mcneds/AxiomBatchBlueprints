package dev.aiden.axiombatch;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class AxiomBatchBlueprintsClient implements ClientModInitializer {
    private static Batch batch;
    private static boolean choosingSources;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("axiombatchbp")
                .executes(ctx -> chooseFiles(ctx.getSource()))
                .then(ClientCommands.argument("source", StringArgumentType.string())
                    .then(ClientCommands.argument("destination", StringArgumentType.string())
                        .executes(ctx -> startBatch(
                            ctx.getSource(),
                            resolvePath(StringArgumentType.getString(ctx, "source")),
                            resolvePath(StringArgumentType.getString(ctx, "destination"))
                        ))
                    )
                )
                .then(ClientCommands.literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(
                        batch == null ? "No batch started" : batch.status()
                    ));
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
            if (batch != null && batch.running()) batch.tick();
        });
    }

    private static int chooseFiles(FabricClientCommandSource source) {
        if (isBusy(source)) return 0;
        choosingSources = true;

        Minecraft client = Minecraft.getInstance();

        try {
            AxiomRuntime axiom = new AxiomRuntime();
            source.sendFeedback(Component.literal("Choose one or more .schem files..."));

            NativeDialogs.openSchematicFiles(axiom, sourcePickerStart().toString())
                .whenComplete((selected, error) -> client.submit(() -> {
                    if (error != null) {
                        choosingSources = false;
                        error.printStackTrace();
                        source.sendError(Component.literal(
                            "File picker failed: " + rootMessage(error)
                        ));
                        return;
                    }

                    if (selected == null || selected.isEmpty()) {
                        choosingSources = false;
                        source.sendFeedback(Component.literal("File selection cancelled."));
                        return;
                    }

                    rememberSourceDirectory(selected.getFirst().getParent());

                    source.sendFeedback(Component.literal(
                        "Selected " + selected.size() +
                        " schematic(s). Choose destination folder..."
                    ));

                    try {
                        axiom.openFolderDialog(blueprintRoot().toString())
                            .whenComplete((destination, folderError) ->
                                client.submit(() -> {
                                    choosingSources = false;

                                    if (folderError != null) {
                                        folderError.printStackTrace();
                                        source.sendError(Component.literal(
                                            "Destination picker failed: " +
                                            rootMessage(folderError)
                                        ));
                                        return;
                                    }

                                    if (destination == null) {
                                        source.sendFeedback(Component.literal(
                                            "Destination selection cancelled."
                                        ));
                                        return;
                                    }

                                    startBatch(
                                        source,
                                        selected,
                                        Path.of(destination)
                                            .toAbsolutePath()
                                            .normalize()
                                    );
                                })
                            );
                    } catch (Throwable t) {
                        choosingSources = false;
                        t.printStackTrace();
                        source.sendError(Component.literal(
                            "Could not open destination picker: " + rootMessage(t)
                        ));
                    }
                }));

            return 1;
        } catch (Throwable t) {
            choosingSources = false;
            t.printStackTrace();
            source.sendError(Component.literal(
                "Could not open file picker: " + rootMessage(t)
            ));
            return 0;
        }
    }

    private static int startBatch(
        FabricClientCommandSource source,
        Path input,
        Path output
    ) {
        if (isBusy(source)) return 0;

        if (!Files.exists(input)) {
            source.sendError(Component.literal("Source does not exist: " + input));
            return 0;
        }

        if (Files.isRegularFile(input) && !isSchematic(input)) {
            source.sendError(Component.literal("Source file must end in .schem: " + input));
            return 0;
        }

        if (!Files.isDirectory(input) && !Files.isRegularFile(input)) {
            source.sendError(Component.literal(
                "Source must be a .schem file or directory: " + input
            ));
            return 0;
        }

        try {
            validateDestination(output);
            rememberSourceDirectory(Files.isDirectory(input) ? input : input.getParent());
            batch = new Batch(Minecraft.getInstance(), input, output);
            return reportStarted(source, output);
        } catch (Throwable t) {
            return reportFailure(source, t);
        }
    }

    private static int startBatch(
        FabricClientCommandSource source,
        List<Path> selected,
        Path output
    ) {
        if (batch != null && batch.running()) {
            source.sendError(Component.literal(
                "A batch is already running. Use /axiombatchbp status or cancel."
            ));
            return 0;
        }

        try {
            validateDestination(output);

            List<Path> valid = selected.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .filter(AxiomBatchBlueprintsClient::isSchematic)
                .toList();

            if (valid.isEmpty()) {
                source.sendError(Component.literal("No valid .schem files selected."));
                return 0;
            }

            rememberSourceDirectory(valid.getFirst().getParent());
            batch = new Batch(Minecraft.getInstance(), valid, output);
            return reportStarted(source, output);
        } catch (Throwable t) {
            return reportFailure(source, t);
        }
    }

    private static boolean isBusy(FabricClientCommandSource source) {
        if (batch != null && batch.running()) {
            source.sendError(Component.literal(
                "A batch is already running. Use /axiombatchbp status or cancel."
            ));
            return true;
        }

        if (choosingSources) {
            source.sendError(Component.literal("A file picker is already open."));
            return true;
        }

        return false;
    }

    private static int reportStarted(FabricClientCommandSource source, Path output) {
        source.sendFeedback(Component.literal(
            "Started: " + batch.total() + " schematic(s) -> " + output
        ));
        return 1;
    }

    private static int reportFailure(FabricClientCommandSource source, Throwable t) {
        t.printStackTrace();
        source.sendError(Component.literal("Failed to start: " + rootMessage(t)));
        return 0;
    }

    private static void validateDestination(Path output) throws Exception {
        if (Files.exists(output) && !Files.isDirectory(output)) {
            throw new IllegalArgumentException(
                "Destination must be a directory: " + output
            );
        }
        Files.createDirectories(output);
    }

    static boolean isSchematic(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".schem");
    }

    static String stripSchem(String name) {
        return name.toLowerCase().endsWith(".schem")
            ? name.substring(0, name.length() - ".schem".length())
            : name;
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static Path blueprintRoot() {
        return FabricLoader.getInstance().getGameDir()
            .resolve("config")
            .resolve("axiom")
            .resolve("blueprints")
            .toAbsolutePath()
            .normalize();
    }

    private static Path sourcePickerStart() {
        Path fallback = blueprintRoot();
        Path state = lastSourceState();

        try {
            if (!Files.isRegularFile(state)) return fallback;

            String raw = Files.readString(state, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return fallback;

            Path path = Path.of(raw).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void rememberSourceDirectory(Path directory) {
        if (directory == null) return;

        try {
            Path normalized = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized)) return;

            Path state = lastSourceState();
            Files.createDirectories(state.getParent());
            Files.writeString(
                state,
                normalized.toString(),
                StandardCharsets.UTF_8
            );
        } catch (Throwable t) {
            System.err.println(
                "[AxiomBatchBP] Could not remember source directory: " +
                rootMessage(t)
            );
        }
    }

    private static Path lastSourceState() {
        return FabricLoader.getInstance().getConfigDir()
            .resolve("axiom-batch-blueprints-last-source.txt");
    }

    private static Path resolvePath(String raw) {
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : blueprintRoot().resolve(path))
            .toAbsolutePath()
            .normalize();
    }
}
