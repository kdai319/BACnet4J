/*
 * ============================================================================
 * GNU General Public License
 * ============================================================================
 *
 * Copyright (C) 2015 Infinite Automation Software. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * When signing a commercial license with Infinite Automation Software,
 * the following extension to GPL is made. A special exception to the GPL is
 * included to allow you to distribute a combined work that includes BAcnet4J
 * without being obliged to provide the source code for any proprietary components.
 *
 * See www.infiniteautomation.com for commercial license options.
 *
 * @author Matthew Lohbihler
 */
package com.serotonin.bacnet4j.obj;

import java.time.Clock;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.ResponseConsumer;
import com.serotonin.bacnet4j.apdu.AckAPDU;
import com.serotonin.bacnet4j.enums.DayOfWeek;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetRuntimeException;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.obj.mixin.HasStatusFlagsMixin;
import com.serotonin.bacnet4j.obj.mixin.intrinsicReporting.IntrinsicReportingMixin;
import com.serotonin.bacnet4j.obj.mixin.intrinsicReporting.NoneAlgo;
import com.serotonin.bacnet4j.service.acknowledgement.AcknowledgementService;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.BACnetArray;
import com.serotonin.bacnet4j.type.constructed.DailySchedule;
import com.serotonin.bacnet4j.type.constructed.DateRange;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.DeviceObjectPropertyReference;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.SpecialEvent;
import com.serotonin.bacnet4j.type.constructed.StatusFlags;
import com.serotonin.bacnet4j.type.constructed.TimeValue;
import com.serotonin.bacnet4j.type.enumerated.ErrorClass;
import com.serotonin.bacnet4j.type.enumerated.ErrorCode;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.NotifyType;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.enumerated.Reliability;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.Date;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Primitive;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

public class ScheduleObject<T extends Primitive> extends BACnetObject {
    private static final long serialVersionUID = 1660874501874089852L;
    static final Logger LOG = LoggerFactory.getLogger(ScheduleObject.class);

    private ScheduledFuture<?> presentValueRefersher;
    private final Clock clock;

    /**
     * A proprietary mechanism to periodically write the present value to all property references in case of power
     * failures, restarts, and the like.
     */
    private ScheduledFuture<?> periodicWriter;

    public ScheduleObject(final int instanceNumber, final String name, final DateRange effectivePeriod,
            final BACnetArray<DailySchedule> weeklySchedule, final SequenceOf<SpecialEvent> exceptionSchedule,
            final T scheduleDefault, final SequenceOf<DeviceObjectPropertyReference> listOfObjectPropertyReferences,
            final int priorityForWriting, final boolean outOfService, final Clock clock) {
        super(ObjectType.schedule, instanceNumber, name);
        this.clock = clock;

        if (effectivePeriod == null)
            throw new BACnetRuntimeException("effectivePeriod cannot be null");
        if (weeklySchedule == null && exceptionSchedule == null)
            throw new BACnetRuntimeException("Both weeklySchedule and exceptionSchedule cannot be null");
        if (scheduleDefault == null)
            throw new BACnetRuntimeException("scheduleDefault cannot be null");
        if (listOfObjectPropertyReferences == null)
            throw new BACnetRuntimeException("listOfObjectPropertyReferences cannot be null");

        writePropertyInternal(PropertyIdentifier.effectivePeriod, effectivePeriod);
        if (weeklySchedule != null) {
            if (weeklySchedule.getCount() != 7)
                throw new BACnetRuntimeException("weeklySchedule must have 7 elements");
            writePropertyInternal(PropertyIdentifier.weeklySchedule, weeklySchedule);
        }
        if (exceptionSchedule != null)
            writePropertyInternal(PropertyIdentifier.exceptionSchedule, exceptionSchedule);
        writePropertyInternal(PropertyIdentifier.scheduleDefault, scheduleDefault);
        writePropertyInternal(PropertyIdentifier.presentValue, scheduleDefault);
        writePropertyInternal(PropertyIdentifier.listOfObjectPropertyReferences, listOfObjectPropertyReferences);
        writePropertyInternal(PropertyIdentifier.priorityForWriting, new UnsignedInteger(priorityForWriting));
        writePropertyInternal(PropertyIdentifier.reliability, Reliability.noFaultDetected);
        writePropertyInternal(PropertyIdentifier.outOfService,
                new com.serotonin.bacnet4j.type.primitive.Boolean(outOfService));
        writePropertyInternal(PropertyIdentifier.statusFlags, new StatusFlags(false, false, false, outOfService));

        addMixin(new HasStatusFlagsMixin(this));
        addMixin(new ScheduleMixin(this));

        // Validations
        // 1) entries in the list of property references must reference properties of this type
        // 2) time value entries in the weekly and exception schedules must be of this type
        // 3) time values must have times that are fully specific.
    }

    public void supportIntrinsicReporting(final int notificationClass, final EventTransitionBits eventEnable,
            final NotifyType notifyType) {
        // Prepare the object with all of the properties that intrinsic reporting will need.
        // User-defined properties
        writePropertyInternal(PropertyIdentifier.notificationClass, new UnsignedInteger(notificationClass));
        writePropertyInternal(PropertyIdentifier.eventEnable, eventEnable);
        writePropertyInternal(PropertyIdentifier.eventState, EventState.normal);
        writePropertyInternal(PropertyIdentifier.notifyType, notifyType);

        // Now add the mixin.
        addMixin(new IntrinsicReportingMixin(this, new NoneAlgo(this), null, new PropertyIdentifier[0],
                new PropertyIdentifier[0]));
    }

    /**
     * Starts the internal periodic writer.
     *
     * @param delay
     *            the delay before the first execution, in milliseconds.
     * @param period
     *            the period between executions, in milliseconds.
     */
    public void startPeriodicWriter(final long delay, final long period) {
        if (delay < 0)
            throw new IllegalArgumentException("delay cannot be < 0");
        if (period < 1)
            throw new IllegalArgumentException("period cannot be < 1");

        cancelPeriodicWriter();
        periodicWriter = getLocalDevice().scheduleAtFixedRate(() -> forceWrites(), delay, period,
                TimeUnit.MILLISECONDS);
        LOG.debug("Periodic writer started");
    }

    public void stopPeriodicWriter() {
        cancelPeriodicWriter();
    }

    @Override
    public void addedToDevice() {
        final T oldValue = get(PropertyIdentifier.presentValue);
        updatePresentValue();
        final T newValue = get(PropertyIdentifier.presentValue);
        // If the present value didn't change after the update, then no write would have been done. So, to ensure
        // initialization of the objects in the list, force a write.
        if (Objects.equals(oldValue, newValue))
            doWrites(newValue);
    }

    @Override
    public void removedFromDevice() {
        cancelRefresher();
        cancelPeriodicWriter();
    }

    synchronized public void forceWrites() {
        doWrites(get(PropertyIdentifier.presentValue));
    }

    class ScheduleMixin extends AbstractMixin {
        public ScheduleMixin(final BACnetObject bo) {
            super(bo);
        }

        @Override
        protected boolean validateProperty(final PropertyValue value) throws BACnetServiceException {
            if (PropertyIdentifier.presentValue.equals(value.getPropertyIdentifier())) {
                final Boolean outOfService = get(PropertyIdentifier.outOfService);
                if (!outOfService.booleanValue())
                    throw new BACnetServiceException(ErrorClass.property, ErrorCode.writeAccessDenied);
            }
            return false;
        }

        @Override
        protected void afterWriteProperty(final PropertyIdentifier pid, final Encodable oldValue,
                final Encodable newValue) {
            if (Objects.equals(newValue, oldValue))
                return;

            if (pid.isOneOf(PropertyIdentifier.effectivePeriod, PropertyIdentifier.weeklySchedule,
                    PropertyIdentifier.exceptionSchedule, PropertyIdentifier.scheduleDefault))
                updatePresentValue();
            if (pid.equals(PropertyIdentifier.presentValue))
                doWrites(newValue);
        }
    }

    private void cancelRefresher() {
        if (presentValueRefersher != null) {
            presentValueRefersher.cancel(false);
            presentValueRefersher = null;
        }
    }

    private void cancelPeriodicWriter() {
        if (periodicWriter != null) {
            periodicWriter.cancel(false);
            periodicWriter = null;
        }
    }

    synchronized void updatePresentValue() {
        final GregorianCalendar gc = new GregorianCalendar();
        gc.setTimeInMillis(clock.millis());
        updatePresentValue(new DateTime(gc));
    }

    @SuppressWarnings("unchecked")
    private void updatePresentValue(final DateTime now) {
        cancelRefresher();

        T newValue;
        long nextCheck;

        final T scheduleDefault = get(PropertyIdentifier.scheduleDefault);
        final DateRange effectivePeriod = get(PropertyIdentifier.effectivePeriod);
        if (!effectivePeriod.matches(now.getDate())) {
            // Not in the current effective date.
            newValue = scheduleDefault;
            nextCheck = nextDay(now);
        } else {
            SequenceOf<TimeValue> schedule = null;

            // Is there an exception schedule in effect?
            final SpecialEvent specialEvent = findExceptionSchedule(now);
            if (specialEvent != null)
                schedule = specialEvent.getListOfTimeValues();
            else {
                final DailySchedule dailySchedule = findDailySchedule(now);
                if (dailySchedule != null)
                    schedule = dailySchedule.getDaySchedule();
            }

            if (schedule == null) {
                newValue = scheduleDefault;
                nextCheck = nextDay(now);
            } else {
                // Find the schedule entry in effect at this time.
                TimeValue currentTv = null;
                int tvIndex = schedule.getCount();
                for (; tvIndex > 0; tvIndex--) {
                    final TimeValue tv = schedule.get(tvIndex);

                    if (!tv.getTime().after(now.getTime())) {
                        // Found a time value entry that can be used.
                        currentTv = tv;
                        break;
                    }
                }

                // Determine the new present value.
                if (currentTv == null)
                    newValue = scheduleDefault;
                else
                    newValue = (T) currentTv.getValue();

                // Determine the next time this method should run.
                if (tvIndex < schedule.getCount()) {
                    final TimeValue nextTv = schedule.get(tvIndex + 1);
                    nextCheck = timeOf(now.getDate(), nextTv);
                } else
                    nextCheck = nextDay(now);
            }
        }

        writePropertyInternal(PropertyIdentifier.presentValue, newValue);

        final java.util.Date nextRuntime = new java.util.Date(nextCheck);
        presentValueRefersher = getLocalDevice().schedule(() -> updatePresentValue(), nextRuntime.getTime(),
                TimeUnit.MILLISECONDS);
        LOG.debug("Timer scheduled to run at {}", nextRuntime);
    }

    private static long nextDay(final DateTime now) {
        final GregorianCalendar gc = now.getGC();
        gc.add(Calendar.DATE, 1);
        gc.add(Calendar.HOUR_OF_DAY, -gc.get(Calendar.HOUR_OF_DAY));
        gc.add(Calendar.MINUTE, -gc.get(Calendar.MINUTE));
        gc.add(Calendar.SECOND, -gc.get(Calendar.SECOND));
        gc.add(Calendar.MILLISECOND, -gc.get(Calendar.MILLISECOND));
        return gc.getTimeInMillis();
    }

    private static long timeOf(final Date date, final TimeValue tv) {
        final DateTime dt = new DateTime(date, tv.getTime());
        return dt.getGC().getTimeInMillis();
    }

    private SpecialEvent findExceptionSchedule(final DateTime now) {
        final SequenceOf<SpecialEvent> exceptionSchedule = get(PropertyIdentifier.exceptionSchedule);
        if (exceptionSchedule == null)
            return null;

        SpecialEvent best = null;
        for (final SpecialEvent e : exceptionSchedule) {
            boolean active;
            if (e.isCalendarReference()) {
                final CalendarObject co = (CalendarObject) getLocalDevice().getObject(e.getCalendarReference());
                if (co != null) {
                    Boolean pv;
                    try {
                        // Getting the property this way ensures that the calendar's present value gets is calculated.
                        pv = co.getProperty(PropertyIdentifier.presentValue);
                        active = pv.booleanValue();
                    } catch (final BACnetServiceException e1) {
                        // Should never happen.
                        throw new RuntimeException(e1);
                    }
                } else
                    active = false;
            } else
                active = e.getCalendarEntry().matches(now.getDate());

            if (active) {
                if (best == null || best.getEventPriority().intValue() > e.getEventPriority().intValue())
                    best = e;
            }
        }
        return best;
    }

    private DailySchedule findDailySchedule(final DateTime now) {
        final BACnetArray<DailySchedule> weeklySchedule = get(PropertyIdentifier.weeklySchedule);
        if (weeklySchedule == null)
            return null;

        DayOfWeek dow = now.getDate().getDayOfWeek();
        if (!dow.isSpecific())
            dow = DayOfWeek.forDate(now.getDate());

        return weeklySchedule.get(dow.getId());
    }

    void doWrites(final Encodable value) {
        final SequenceOf<DeviceObjectPropertyReference> listOfObjectPropertyReferences = get(
                PropertyIdentifier.listOfObjectPropertyReferences);
        final UnsignedInteger priorityForWriting = get(PropertyIdentifier.priorityForWriting);

        // Send the write requests.
        for (final DeviceObjectPropertyReference dopr : listOfObjectPropertyReferences) {
            LOG.debug("Sending write request to {} in {}, value={}, priority={}", dopr.getObjectIdentifier(),
                    dopr.getDeviceIdentifier(), value, priorityForWriting);

            if (dopr.getDeviceIdentifier() == null) {
                // Local write.
                final BACnetObject that = getLocalDevice().getObject(dopr.getObjectIdentifier());
                try {
                    that.writeProperty(new PropertyValue(dopr.getPropertyIdentifier(), dopr.getPropertyArrayIndex(),
                            value, priorityForWriting));
                } catch (final BACnetServiceException e) {
                    LOG.warn("Schedule failed to write to local object {}", dopr.getObjectIdentifier(), e);
                }
            } else {
                final ObjectIdentifier devId = dopr.getDeviceIdentifier();
                final ObjectIdentifier oid = dopr.getObjectIdentifier();

                try {
                    final RemoteDevice d = getLocalDevice().getRemoteDevice(devId.getInstanceNumber()).get();
                    final WritePropertyRequest req = new WritePropertyRequest(oid, dopr.getPropertyIdentifier(),
                            dopr.getPropertyArrayIndex(), value, priorityForWriting);
                    getLocalDevice().send(d, req, new ResponseConsumer() {
                        @Override
                        public void success(final AcknowledgementService ack) {
                            // Whatever.
                        }

                        @Override
                        public void fail(final AckAPDU ack) {
                            LOG.warn("Schedule failed to write to {} in {}, ack={}", oid, devId, ack);
                        }

                        @Override
                        public void ex(final BACnetException e) {
                            LOG.error("Schedule failed to write to {} in {}", oid, devId, e);
                        }
                    });
                } catch (final BACnetException e) {
                    LOG.warn("Schedule failed to write to unknown remote device {}", devId, e);
                }
            }
        }
    }
}
