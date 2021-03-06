/*
 * Copyright 2017 Jan-Piet Mens (jpmens@gmail.com)
 * Copyright 2017 - 2018 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.traccar.protocol;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.traccar.BaseHttpProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.Context;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;
import org.traccar.model.Event;
import org.traccar.model.Device;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class OwnTracksProtocolDecoder extends BaseHttpProtocolDecoder {

    public OwnTracksProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        FullHttpRequest request = (FullHttpRequest) msg;
        JsonObject root = Json.createReader(
                new StringReader(request.content().toString(StandardCharsets.US_ASCII))).readObject();

        if (!root.containsKey("_type")) {
            sendResponse(channel, HttpResponseStatus.OK);
            return null;
        }
        if (!root.getString("_type").equals("location")
            && !root.getString("_type").equals("lwt")) {
            sendResponse(channel, HttpResponseStatus.BAD_REQUEST);
            return null;
        }

        Position position = new Position();
        String uniqueId;

        if (root.containsKey("topic")) {
            uniqueId = root.getString("topic");
            if (root.containsKey("tid")) {
                position.set("tid", root.getString("tid"));
            }
        } else {
            uniqueId = root.getString("tid");
        }
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, uniqueId);
        if (deviceSession == null) {
            sendResponse(channel, HttpResponseStatus.BAD_REQUEST);
            return null;
        }

        if (root.getString("_type").equals("lwt")) {
            Context.getConnectionManager().updateDevice(deviceSession.getDeviceId(), Device.STATUS_OFFLINE, new Date());

            sendResponse(channel, HttpResponseStatus.OK);
            return null;
        }

        if (root.containsKey("t") && root.getString("t").equals("p")) {
            sendResponse(channel, HttpResponseStatus.OK);
            return null;
        }

        position.setDeviceId(deviceSession.getDeviceId());
        position.setProtocol(getProtocolName());
        //position.set(Position.KEY_ORIGINAL, request.getContent().toString(StandardCharsets.US_ASCII));

        position.setTime(new Date(root.getJsonNumber("tst").longValue() * 1000));
        if (root.containsKey("sent")) {
            position.setDeviceTime(new Date(root.getJsonNumber("sent").longValue() * 1000));
        }

        position.setValid(true);

        position.setLatitude(root.getJsonNumber("lat").doubleValue());
        position.setLongitude(root.getJsonNumber("lon").doubleValue());

        if (root.containsKey("vel")) {
            position.setSpeed(UnitsConverter.knotsFromKph(root.getInt("vel")));
        }
        if (root.containsKey("alt")) {
            position.setAltitude(root.getInt("alt"));
        }
        if (root.containsKey("cog")) {
            position.setCourse(root.getInt("cog"));
        }
        if (root.containsKey("acc")) {
            position.setAccuracy(root.getInt("acc"));
        }
        if (root.containsKey("t")) {
            String t = root.getString("t");
            position.set("t", t);
            Integer rty = -1;
            if (root.containsKey("rty")) {
                 rty = root.getInt("rty");
            }
            setEventOrAlarm(position, t, rty);
        }
        if (root.containsKey("batt")) {
            position.set(Position.KEY_BATTERY_LEVEL, root.getInt("batt"));
        }
        if (root.containsKey("uext")) {
            position.set(Position.KEY_POWER, root.getJsonNumber("uext").doubleValue());
        }
        if (root.containsKey("ubatt")) {
            position.set(Position.KEY_BATTERY, root.getJsonNumber("ubatt").doubleValue());
        }
        if (root.containsKey("vin")) {
            position.set(Position.KEY_VIN, root.getString("vin"));
        }
        if (root.containsKey("name")) {
            position.set(Position.KEY_VIN, root.getString("name"));
        }
        if (root.containsKey("rpm")) {
            position.set(Position.KEY_RPM, root.getInt("rpm"));
        }
        if (root.containsKey("ign")) {
            position.set(Position.KEY_IGNITION, root.getBoolean("ign"));
        }
        if (root.containsKey("motion")) {
            position.set(Position.KEY_MOTION, root.getBoolean("motion"));
        }
        if (root.containsKey("odometer")) {
            position.set(Position.KEY_ODOMETER, root.getJsonNumber("odometer").doubleValue() * 1000.0);
        }
        if (root.containsKey("hmc")) {
            position.set(Position.KEY_HOURS, root.getJsonNumber("hmc").doubleValue() / 3600.0);
        }

        if (root.containsKey("anum")) {
            Integer anum = root.getInt("anum");
            for (Integer i = 0; i < anum; i++) {
                String indexString = String.format("%02d", i);
                if (root.containsKey("adda-" + indexString)) {
                    position.set(Position.PREFIX_ADC + (i + 1), root.getString("adda-" + indexString));
                }
                if (root.containsKey("temp_c-" + indexString)) {
                    position.set(Position.PREFIX_TEMP + (i + 1),
                        root.getJsonNumber("temp_c-" + indexString).doubleValue());
                }
            }
        }

        sendResponse(channel, HttpResponseStatus.OK);
        return position;
    }

    private void setEventOrAlarm(Position position, String t, Integer rty) {
        if (t.equals("9")) {
            position.set(Position.KEY_EVENT, Event.TYPE_ALARM);
            position.set(Position.KEY_ALARM, Position.ALARM_LOW_BATTERY);
        } else if (t.equals("1")) {
            position.set(Position.KEY_EVENT, Event.TYPE_ALARM);
            position.set(Position.KEY_ALARM, Position.ALARM_POWER_ON);
        } else if (t.equals("i")) {
            //position.set(Position.KEY_EVENT, Event.TYPE_IGNITION_ON);
            position.set(Position.KEY_IGNITION, true);
        } else if (t.equals("I")) {
            //position.set(Position.KEY_EVENT, Event.TYPE_IGNITION_OFF);
            position.set(Position.KEY_IGNITION, false);
        } else if (t.equals("E")) {
            position.set(Position.KEY_EVENT, Event.TYPE_ALARM);
            position.set(Position.KEY_ALARM, Position.ALARM_POWER_RESTORED);
        } else if (t.equals("e")) {
            position.set(Position.KEY_EVENT, Event.TYPE_ALARM);
            position.set(Position.KEY_ALARM, Position.ALARM_POWER_CUT);
        } else if (t.equals("!")) {
            position.set(Position.KEY_EVENT, Event.TYPE_ALARM);
            position.set(Position.KEY_ALARM, Position.ALARM_TOW);
        } else if (t.equals("s")) {
            position.set(Position.KEY_EVENT, Event.TYPE_ALARM);
            position.set(Position.KEY_ALARM, Position.ALARM_OVERSPEED);
        } else if (t.equals("h")) {
            position.set(Position.KEY_EVENT, Event.TYPE_ALARM);
            switch (rty) {
                case 0:
                case 3:
                    position.set(Position.KEY_ALARM, Position.ALARM_BRAKING);
                    break;
                case 1:
                case 4:
                    position.set(Position.KEY_ALARM, Position.ALARM_ACCELERATION);
                    break;
                case 2:
                case 5:
                default:
                    position.set(Position.KEY_ALARM, Position.ALARM_CORNERING);
                    break;
            }
        }
    }
}
