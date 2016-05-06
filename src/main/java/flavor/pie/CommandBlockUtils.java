package flavor.pie;

import java.io.FileNotFoundException;
import java.net.*;
import java.util.*;

import org.spongepowered.api.Game;
import org.spongepowered.api.command.*;
import org.spongepowered.api.command.args.*;
import org.spongepowered.api.command.source.LocatedSource;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.data.*;
import org.spongepowered.api.data.key.Key;
import org.spongepowered.api.data.key.KeyFactory;
import org.spongepowered.api.data.manipulator.DataManipulatorBuilder;
import org.spongepowered.api.data.manipulator.immutable.common.AbstractImmutableMappedData;
import org.spongepowered.api.data.manipulator.mutable.common.AbstractMappedData;
import org.spongepowered.api.data.merge.MergeFunction;
import org.spongepowered.api.data.persistence.DataBuilder;
import org.spongepowered.api.data.value.BaseValue;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.command.SendCommandEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePostInitializationEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.resourcepack.ResourcePack;
import org.spongepowered.api.resourcepack.ResourcePacks;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.storage.WorldProperties;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;

@Plugin(id="commandblockutils",name="CommandBlockUtils",version="1.0.0-SNAPSHOT",authors="pie_flavor",description="Utilities for command blocks.")
public class CommandBlockUtils {
    Key<? extends BaseValue<Map<String, String>>> key;
    VariableDataBuilder builder;
    @Inject Game game;
    @Listener
    public void onInitialize(GameInitializationEvent e) {
        key = KeyFactory.makeMapKey(String.class, String.class, DataQuery.of("variables"));
        builder = new VariableDataBuilder(key);
        game.getDataManager().register(VariableData.class, ImmutableVariableData.class, builder);
    }
    @Listener
    public void onPostInitialize(GamePostInitializationEvent e) {
        game.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Sets a player's resource pack."))
                .executor(this::resourcepack)
                .arguments(
                        GenericArguments.player(Text.of("player")),
                        new URIElement(Text.of("url")))
                .permission("cbu.resourcepack")
                .build(), "resourcepack");
        game.getCommandManager().register(this, CommandSpec.builder()
                .description(Text.of("Sets a variable for use with commands."))
                .extendedDescription(Text.of("These can be referenced in other commands with $$<varname>."))
                .arguments(GenericArguments.flags()
                        .valueFlag(GenericArguments.world(Text.of("world")), "-world")
                        .valueFlag(GenericArguments.player(Text.of("player")), "-player")
                        .valueFlag(GenericArguments.location(Text.of("location")), "-block")
                        .buildWith(GenericArguments.seq(
                                GenericArguments.choices(Text.of("extent"), ImmutableMap.of("world","world","source","source")),
                                GenericArguments.string(Text.of("var")),
                                GenericArguments.string(Text.of("value")))))
                .permission("cbu.setvar.use")
                .executor(this::var)
                .build(), "setvar");
    }
    public CommandResult resourcepack(CommandSource src, CommandContext args) throws CommandException {
        Collection<Player> players = args.getAll("player");
        URI uri = args.<URI>getOne("url").get();
        ResourcePack pack;
        try {
            pack = ResourcePacks.fromUri(uri);
        } catch (FileNotFoundException e) {
            throw new CommandException(Text.of(TextColors.RED, "Invalid URL!"));
        }
        int count = 0;
        for (Player p : players) {
            p.sendResourcePack(pack);
            count += 1;
        }
        return CommandResult.builder().affectedEntities(count).successCount(count).queryResult(count).build();
    }
    public CommandResult var(CommandSource src, CommandContext args) throws CommandException {
        String extent = args.<String>getOne("extent").get();
        String var = args.<String>getOne("var").get();
        String val = args.<String>getOne("value").get();
        switch (extent) {
            case "world":
                args.checkPermission(src, "cbu.setvar.world.use");
                WorldProperties world;
                if (args.hasAny("world")) {
                    args.checkPermission(src, "cbu.setvar.world.other");
                    world = args.<WorldProperties>getOne("world").get();
                } else {
                    if (src instanceof LocatedSource){
                        world = ((LocatedSource) src).getWorld().getProperties();
                    } else {
                        throw new CommandException(Text.of(TextColors.RED, "&cYou do not have a Location, so you must use the --world=<worldname> flag."));
                    }
                }
                DataQuery query = DataQuery.of('.', "commandblockutils");
                Optional<DataView> opt = world.getPropertySection(query);
                VariableData data;
                if (opt.isPresent()) {
                    data = builder.build(opt.get()).get();
                } else {
                    builder.reset();
                    data = builder.create();
                }
                data.put(var, val);
                world.setPropertySection(query, data.toContainer());
                return CommandResult.success();
            case "source":
                if (args.hasAny("block")) {
                    Location<World> loc = args.<Location<World>>getOne("block").get();
                    data = loc.getExtent().getOrCreate(loc.getBlockPosition(), VariableData.class).get();
                    data.put(var, val);
                    loc.getExtent().offer(loc.getBlockPosition(), data);
                } else if (args.hasAny("player")) {
                    Player p = args.<Player>getOne("player").get();
                    data = p.getOrCreate(VariableData.class).get();
                    data.put(var, val);
                    p.offer(data);
                } else {
                    if (src instanceof DataHolder) {
                        DataHolder holder = (DataHolder) src;
                        data = holder.getOrCreate(VariableData.class).get();
                        data.put(var, val);
                        holder.offer(data);
                    } else {
                        throw new CommandException(Text.of(TextColors.RED, "You cannot store data, so you must use either --block or --player!"));
                    }
                }
        }
        return CommandResult.success();
    }
    static class URIElement extends CommandElement {
        protected URIElement(Text key) {
            super(key);
        }
        @Override
        protected Object parseValue(CommandSource source, CommandArgs args) throws ArgumentParseException {
            try {
                URI url = new URL(args.next()).toURI();
                return url;
            } catch (MalformedURLException | URISyntaxException e) {
                throw args.createError(Text.of(TextColors.RED, "Invalid URL!"));
            }
        }

        @Override
        public List<String> complete(CommandSource src, CommandArgs args, CommandContext context) {
            return ImmutableList.of();
        }
    }
    @Listener(order = Order.PRE)
    public void onSendCommand(SendCommandEvent e, @First LocatedSource src) {
        Map<String, String> map = new HashMap<>();
        WorldProperties properties = src.getLocation().getExtent().getProperties();
        Optional<DataView> view_ = properties.getPropertySection(DataQuery.of('.',"commandblockutils"));
        if (view_.isPresent()) {
            DataView view = view_.get();
            Optional<VariableData> worldData_ = builder.build(view);
            if (worldData_.isPresent()) {
                VariableData worldData = worldData_.get();
                map.putAll(worldData.get(key).orElse(new HashMap<>()));
            }
        }
        if (src instanceof DataHolder) {
            DataHolder holder = (DataHolder) src;
            if (holder.supports(key)) {
                VariableData data = holder.getOrCreate(VariableData.class).get();
                map.putAll(data.get(key).orElse(new HashMap<>()));
            }
        }
        String arguments = e.getArguments();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            arguments = arguments.replace("$$"+entry.getKey(), entry.getValue());
        }
        e.setArguments(arguments);
    }
    static class VariableData extends AbstractMappedData<String, String, VariableData, ImmutableVariableData> {
        Map<String, String> map;
        Key<? extends BaseValue<Map<String, String>>> key;
        protected VariableData(Map<String, String> value, Key<? extends BaseValue<Map<String, String>>> usedKey) {
            super(value, usedKey);
            map = value;
            key = usedKey;
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(map.get(key));
        }

        @Override
        public Set<String> getMapKeys() {
            return map.keySet();
        }

        @Override
        public VariableData put(String key, String value) {
            map.put(key, value);
            return this;
        }

        @Override
        public VariableData putAll(Map<? extends String, ? extends String> map) {
            this.map.putAll(map);
            return this;
        }

        @Override
        public VariableData remove(String key) {
            map.remove(key);
            return this;
        }

        @Override
        public Optional<VariableData> fill(DataHolder dataHolder, MergeFunction overlap) {
            Optional<Map<String, String>> opt = dataHolder.get(key);
            if (opt.isPresent()) {
                map.putAll(opt.get());
                return Optional.of(this);
            } else {
                return Optional.empty();
            }
        }

        @Override
        public Optional<VariableData> from(DataContainer container) {
            Optional<Object> opt = container.get(key.getQuery());
            if (opt.isPresent()) {
                map = (Map<String, String>) container.get(key.getQuery()).get();
                return Optional.of(this);
            } else {
                return Optional.empty();
            }
        }

        @Override
        public VariableData copy() {
            return new VariableData(map, key);
        }

        @Override
        public int getContentVersion() {
            return 1;
        }

        @Override
        public ImmutableVariableData asImmutable() {
            return new ImmutableVariableData(map, key);
        }
        @Override
        public DataContainer toContainer() {
            DataContainer container = super.toContainer();
            container.set(key.getQuery(), map);
            return container;
        }
    }
    static class ImmutableVariableData extends AbstractImmutableMappedData<String, String, ImmutableVariableData, VariableData> {
        Map<String, String> map;
        Key<? extends BaseValue<Map<String, String>>> key;
        protected ImmutableVariableData(Map<String, String> value,
                                        Key<? extends BaseValue<Map<String, String>>> usedKey) {
            super(value, usedKey);
            map = value;
            key = usedKey;
        }

        @Override
        public int getContentVersion() {
            return 1;
        }

        @Override
        public VariableData asMutable() {
            return new VariableData(map, key);
        }
        @Override
        public DataContainer toContainer() {
            DataContainer container = super.toContainer();
            container.set(key.getQuery(), map);
            return container;
        }
    }
    static class VariableDataBuilder implements DataManipulatorBuilder<VariableData, ImmutableVariableData> {
        Key<? extends BaseValue<Map<String, String>>> key;
        VariableData data;
        VariableDataBuilder(Key<? extends BaseValue<Map<String, String>>> key) {
            this.key = key;
            data = new VariableData(new HashMap<>(), key);
        }
        @Override
        public Optional<VariableData> build(DataView container) {
            Optional<Object> opt = container.get(key.getQuery());
            if (opt.isPresent()) {
                data = new VariableData((Map<String, String>) opt.get(), key);
                return Optional.of(data);
            } else {
                return Optional.empty();
            }
        }

        @Override
        public DataBuilder<VariableData> from(VariableData value) {
            data = value;
            return this;
        }

        @Override
        public DataBuilder<VariableData> reset() {
            data = new VariableData(new HashMap<String, String>(), key);
            return this;
        }

        @Override
        public VariableData create() {
            return data;
        }

        @Override
        public Optional<VariableData> createFrom(DataHolder dataHolder) {
            Optional<Map<String, String>> opt = dataHolder.get(key);
            if (opt.isPresent()) {
                data = new VariableData(opt.get(), key);
                return Optional.of(data);
            } else if (dataHolder.supports(key)) {
                data = new VariableData(new HashMap<>(), key);
                return Optional.of(data);
            } else {
                return Optional.empty();
            }
        }

    }
}
