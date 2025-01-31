/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.miele.internal.handler;

import static org.openhab.binding.miele.internal.MieleBindingConstants.*;

import java.util.HashMap;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.miele.internal.DeviceUtil;
import org.openhab.binding.miele.internal.FullyQualifiedApplianceIdentifier;
import org.openhab.binding.miele.internal.MieleTranslationProvider;
import org.openhab.binding.miele.internal.api.dto.DeviceClassObject;
import org.openhab.binding.miele.internal.api.dto.DeviceMetaData;
import org.openhab.binding.miele.internal.api.dto.DeviceProperty;
import org.openhab.binding.miele.internal.api.dto.HomeDevice;
import org.openhab.core.i18n.LocaleProvider;
import org.openhab.core.i18n.TranslationProvider;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link MieleApplianceHandler} is an abstract class
 * responsible for handling commands, which are sent to one
 * of the channels of the appliance that understands/"talks"
 * the {@link ApplianceChannelSelector} datapoints
 *
 * @author Karel Goderis - Initial contribution
 * @author Martin Lepsy - Added check for JsonNull result
 * @author Jacob Laursen - Fixed multicast and protocol support (ZigBee/LAN)
 */
@NonNullByDefault
public abstract class MieleApplianceHandler<E extends Enum<E> & ApplianceChannelSelector> extends BaseThingHandler
        implements ApplianceStatusListener {

    private final Logger logger = LoggerFactory.getLogger(MieleApplianceHandler.class);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_DISHWASHER, THING_TYPE_OVEN,
            THING_TYPE_FRIDGE, THING_TYPE_DRYER, THING_TYPE_HOB, THING_TYPE_FRIDGEFREEZER, THING_TYPE_HOOD,
            THING_TYPE_WASHINGMACHINE, THING_TYPE_COFFEEMACHINE);

    protected Gson gson = new Gson();

    protected @Nullable String applianceId;
    protected @Nullable MieleBridgeHandler bridgeHandler;
    protected TranslationProvider i18nProvider;
    protected LocaleProvider localeProvider;
    protected @Nullable MieleTranslationProvider translationProvider;
    private Class<E> selectorType;
    protected String modelID;

    protected Map<String, String> metaDataCache = new HashMap<>();

    public MieleApplianceHandler(Thing thing, TranslationProvider i18nProvider, LocaleProvider localeProvider,
            Class<E> selectorType, String modelID) {
        super(thing);
        this.i18nProvider = i18nProvider;
        this.localeProvider = localeProvider;
        this.selectorType = selectorType;
        this.modelID = modelID;
    }

    public ApplianceChannelSelector getValueSelectorFromChannelID(String valueSelectorText)
            throws IllegalArgumentException {
        E[] enumConstants = selectorType.getEnumConstants();
        if (enumConstants == null) {
            throw new IllegalArgumentException(
                    String.format("Could not get enum constants for value selector: %s", valueSelectorText));
        }
        for (ApplianceChannelSelector c : enumConstants) {
            if (c.getChannelID().equals(valueSelectorText)) {
                return c;
            }
        }

        throw new IllegalArgumentException(String.format("Not valid value selector: %s", valueSelectorText));
    }

    public ApplianceChannelSelector getValueSelectorFromMieleID(String valueSelectorText)
            throws IllegalArgumentException {
        E[] enumConstants = selectorType.getEnumConstants();
        if (enumConstants == null) {
            throw new IllegalArgumentException(
                    String.format("Could not get enum constants for value selector: %s", valueSelectorText));
        }
        for (ApplianceChannelSelector c : enumConstants) {
            if (!c.getMieleID().isEmpty() && c.getMieleID().equals(valueSelectorText)) {
                return c;
            }
        }

        throw new IllegalArgumentException(String.format("Not valid value selector: %s", valueSelectorText));
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Miele appliance handler.");
        final String applianceId = (String) getThing().getConfiguration().getProperties().get(APPLIANCE_ID);
        if (applianceId != null) {
            this.applianceId = applianceId;
            this.onBridgeConnectionResumed();
        }
    }

    public void onBridgeConnectionResumed() {
        Bridge bridge = getBridge();
        if (bridge != null && getMieleBridgeHandler() != null) {
            ThingStatusInfo statusInfo = bridge.getStatusInfo();
            updateStatus(statusInfo.getStatus(), statusInfo.getStatusDetail(), statusInfo.getDescription());
            initializeTranslationProvider(bridge);
        }
    }

    private void initializeTranslationProvider(Bridge bridge) {
        Locale locale = null;
        String language = (String) bridge.getConfiguration().get(LANGUAGE);
        if (language != null && !language.isBlank()) {
            try {
                locale = new Locale.Builder().setLanguageTag(language).build();
            } catch (IllformedLocaleException e) {
                logger.error("Invalid language configured: {}", e.getMessage());
            }
        }
        if (locale == null) {
            logger.debug("No language configured, using system language.");
            this.translationProvider = new MieleTranslationProvider(i18nProvider, localeProvider);
        } else {
            this.translationProvider = new MieleTranslationProvider(i18nProvider, localeProvider, locale);
        }
    }

    @Override
    public void dispose() {
        logger.debug("Handler disposes. Unregistering listener.");
        if (applianceId != null) {
            MieleBridgeHandler bridgeHandler = getMieleBridgeHandler();
            if (bridgeHandler != null) {
                bridgeHandler.unregisterApplianceStatusListener(this);
            }
            applianceId = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Here we could handle commands that are common to all Miele Appliances, but so far I don't know of any
        if (command instanceof RefreshType) {
            // Placeholder for future refinement
            return;
        }
    }

    @Override
    public void onApplianceStateChanged(FullyQualifiedApplianceIdentifier applicationIdentifier,
            DeviceClassObject dco) {
        String applianceId = this.applianceId;
        if (applianceId == null || !applianceId.equals(applicationIdentifier.getApplianceId())) {
            return;
        }

        JsonArray properties = dco.Properties;
        if (properties == null) {
            return;
        }

        for (JsonElement prop : properties.getAsJsonArray()) {
            try {
                DeviceProperty dp = gson.fromJson(prop, DeviceProperty.class);
                if (dp == null) {
                    continue;
                }
                if (!EXTENDED_DEVICE_STATE_PROPERTY_NAME.equals(dp.Name)) {
                    dp.Value = dp.Value.trim();
                    dp.Value = dp.Value.strip();
                }
                onAppliancePropertyChanged(applicationIdentifier, dp);
            } catch (Exception p) {
                // Ignore - this is due to an unrecognized and not yet reverse-engineered array property
            }
        }
    }

    @Override
    public void onAppliancePropertyChanged(FullyQualifiedApplianceIdentifier applicationIdentifier, DeviceProperty dp) {
        String applianceId = this.applianceId;

        if (applianceId == null || !applianceId.equals(applicationIdentifier.getApplianceId())) {
            return;
        }

        this.onAppliancePropertyChanged(dp);
    }

    protected void onAppliancePropertyChanged(DeviceProperty dp) {
        try {
            DeviceMetaData dmd = null;
            if (dp.Metadata == null) {
                String metadata = metaDataCache.get(new StringBuilder().append(dp.Name).toString().trim());
                if (metadata != null) {
                    JsonObject jsonMetadata = (JsonObject) JsonParser.parseString(metadata);
                    dmd = gson.fromJson(jsonMetadata, DeviceMetaData.class);
                    // only keep the enum, if any - that's all we care for events we receive via multicast
                    // all other fields are nulled
                    if (dmd != null) {
                        dmd.LocalizedID = null;
                        dmd.LocalizedValue = null;
                        dmd.Filter = null;
                        dmd.description = null;
                    }
                }
            }
            JsonObject jsonMetadata = dp.Metadata;
            if (jsonMetadata != null) {
                String metadata = jsonMetadata.toString().replace("enum", "MieleEnum");
                JsonObject jsonMetaData = (JsonObject) JsonParser.parseString(metadata);
                dmd = gson.fromJson(jsonMetaData, DeviceMetaData.class);
                metaDataCache.put(new StringBuilder().append(dp.Name).toString().trim(), metadata);
            }

            if (EXTENDED_DEVICE_STATE_PROPERTY_NAME.equals(dp.Name)) {
                if (!dp.Value.isEmpty()) {
                    byte[] extendedStateBytes = DeviceUtil.stringToBytes(dp.Value);
                    logger.trace("Extended device state for {}: {}", getThing().getUID(),
                            DeviceUtil.bytesToHex(extendedStateBytes));
                    if (this instanceof ExtendedDeviceStateListener) {
                        ((ExtendedDeviceStateListener) this).onApplianceExtendedStateChanged(extendedStateBytes);
                    }
                }
                return;
            }

            ApplianceChannelSelector selector = null;
            try {
                selector = getValueSelectorFromMieleID(dp.Name);
            } catch (Exception h) {
                logger.trace("{} is not a valid channel for a {}", dp.Name, modelID);
            }

            String dpValue = dp.Value.strip().trim();

            if (selector != null) {
                if (!selector.isProperty()) {
                    ChannelUID theChannelUID = new ChannelUID(getThing().getUID(), selector.getChannelID());

                    State state = selector.getState(dpValue, dmd, this.translationProvider);
                    logger.trace("Update state of {} with getState '{}'", theChannelUID, state);
                    updateState(theChannelUID, state);
                    updateRawChannel(dp.Name, dpValue);
                } else {
                    logger.debug("Updating the property '{}' of '{}' to '{}'", selector.getChannelID(),
                            getThing().getUID(), selector.getState(dpValue, dmd, this.translationProvider).toString());
                    Map<String, String> properties = editProperties();
                    properties.put(selector.getChannelID(),
                            selector.getState(dpValue, dmd, this.translationProvider).toString());
                    updateProperties(properties);
                }
            }
        } catch (IllegalArgumentException e) {
            logger.error("An exception occurred while processing a changed device property :'{}'", e.getMessage());
        }
    }

    protected void updateExtendedState(String channelId, State state) {
        ChannelUID channelUid = new ChannelUID(getThing().getUID(), channelId);
        logger.trace("Update state of {} with extended state '{}'", channelUid, state);
        updateState(channelUid, state);
    }

    /**
     * Update raw value channels for properties already mapped to text channels.
     * Currently ApplianceChannelSelector only supports 1:1 mapping from property
     * to channel.
     */
    private void updateRawChannel(String propertyName, String value) {
        String channelId;
        switch (propertyName) {
            case STATE_PROPERTY_NAME:
                channelId = STATE_CHANNEL_ID;
                break;
            case PROGRAM_ID_PROPERTY_NAME:
                channelId = PROGRAM_CHANNEL_ID;
                break;
            default:
                return;
        }
        ApplianceChannelSelector selector = null;
        try {
            selector = getValueSelectorFromChannelID(channelId);
        } catch (IllegalArgumentException e) {
            logger.trace("{} is not a valid channel for a {}", channelId, modelID);
            return;
        }
        ChannelUID channelUid = new ChannelUID(getThing().getUID(), channelId);
        State state = selector.getState(value);
        logger.trace("Update state of {} with getState '{}'", channelUid, state);
        updateState(channelUid, state);
    }

    @Override
    public void onApplianceRemoved(HomeDevice appliance) {
        String applianceId = this.applianceId;
        if (applianceId == null) {
            return;
        }

        FullyQualifiedApplianceIdentifier applianceIdentifier = appliance.getApplianceIdentifier();
        if (applianceIdentifier == null) {
            return;
        }

        if (applianceId.equals(applianceIdentifier.getApplianceId())) {
            updateStatus(ThingStatus.OFFLINE);
        }
    }

    @Override
    public void onApplianceAdded(HomeDevice appliance) {
        String applianceId = this.applianceId;
        if (applianceId == null) {
            return;
        }

        FullyQualifiedApplianceIdentifier applianceIdentifier = appliance.getApplianceIdentifier();
        if (applianceIdentifier == null) {
            return;
        }

        if (applianceId.equals(applianceIdentifier.getApplianceId())) {
            Map<String, String> properties = editProperties();
            String vendor = appliance.Vendor;
            if (vendor != null) {
                properties.put(Thing.PROPERTY_VENDOR, vendor);
            }
            properties.put(Thing.PROPERTY_MODEL_ID, appliance.getApplianceModel());
            properties.put(Thing.PROPERTY_SERIAL_NUMBER, appliance.getSerialNumber());
            properties.put(Thing.PROPERTY_FIRMWARE_VERSION, appliance.getFirmwareVersion());
            String protocolAdapterName = appliance.ProtocolAdapterName;
            if (protocolAdapterName != null) {
                properties.put(PROPERTY_PROTOCOL_ADAPTER, protocolAdapterName);
            }
            String deviceClass = appliance.getDeviceClass();
            if (deviceClass != null) {
                properties.put(PROPERTY_DEVICE_CLASS, deviceClass);
            }
            String connectionType = appliance.getConnectionType();
            if (connectionType != null) {
                properties.put(PROPERTY_CONNECTION_TYPE, connectionType);
            }
            String connectionBaudRate = appliance.getConnectionBaudRate();
            if (connectionBaudRate != null) {
                properties.put(PROPERTY_CONNECTION_BAUD_RATE, connectionBaudRate);
            }
            updateProperties(properties);
            updateStatus(ThingStatus.ONLINE);
        }
    }

    private synchronized @Nullable MieleBridgeHandler getMieleBridgeHandler() {
        if (this.bridgeHandler == null) {
            Bridge bridge = getBridge();
            if (bridge == null) {
                return null;
            }
            ThingHandler handler = bridge.getHandler();
            if (handler instanceof MieleBridgeHandler) {
                var bridgeHandler = (MieleBridgeHandler) handler;
                this.bridgeHandler = bridgeHandler;
                bridgeHandler.registerApplianceStatusListener(this);
            } else {
                return null;
            }
        }
        return this.bridgeHandler;
    }

    protected boolean isResultProcessable(JsonElement result) {
        return !result.isJsonNull();
    }
}
