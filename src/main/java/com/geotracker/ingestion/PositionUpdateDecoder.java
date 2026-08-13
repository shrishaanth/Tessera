package com.geotracker.ingestion;

import com.geotracker.model.PositionUpdate;
import com.geotracker.util.Config;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class PositionUpdateDecoder extends ByteToMessageDecoder {
    private static final int FRAME_LENGTH = 32;
    private final ShardRouter shardRouter;

    public PositionUpdateDecoder(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < FRAME_LENGTH) {
            return;
        }
        long vehicleId = in.readLong();
        double x = in.readDouble();
        double y = in.readDouble();
        long timestamp = in.readLong();

        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
            return;
        }
        if (x < Config.MAP_MIN_X || x > Config.MAP_MAX_X || y < Config.MAP_MIN_Y || y > Config.MAP_MAX_Y) {
            return;
        }

        PositionUpdate update = new PositionUpdate(vehicleId, x, y, timestamp);
        shardRouter.route(update);
    }
}
