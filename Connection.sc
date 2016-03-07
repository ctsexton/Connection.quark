ConnectionList {
	var <list;

	*newFrom {
		|connectionList|
		^this.newCopyArgs(connectionList.list);
	}

	*new {
		|list|
		^super.newCopyArgs((list ?? List()).asList)
	}

	*makeWith {
		|func|
		Connection.prBeforeCollect();
		protect {
			func.value()
		} {
			^Connection.prAfterCollect();
		}
	}
	connected_{
		|connect|
		list.do(_.connected_(connect));
	}

	connect {
		list.do(_.connect);
	}

	disconnect {
		list.do(_.disconnect);
	}

	connectionFreed {
		this.free;
	}

	free {
		list.do(_.free);
		list = nil;
	}

	disconnectWith {
		|func|
		var wasConnected = list.select(_.connected);

		this.disconnect();

		^func.protect({
			wasConnected.do(_.connect)
		});
	}

	trace {
		|shouldTrace=true|
		list.do(_.trace(shouldTrace));
	}

	dependants {
		^list.collect({ |o| o.dependants.asList }).flatten;
	}

	addDependant {
		|dep|
		list.do(_.addDependant(dep));
	}

	removeDependant {
		|dep|
		list.do(_.removeDependant(dep));
	}

	releaseDependants {
		list.do(_.releaseDependants());
	}

	connectTo {
		|nextDependant, autoConnect=true|
		^Connection(this, nextDependant, autoConnect);
	}

	chain {
		|newDependant|
		list.do(_.chain(newDependant));
	}

	filter {
		|filter|
		list.do(_.filter(filter));
	}

	transform {
		|func|
		list.do(_.transform(func))
	}

	defer {
		|delta=0, clock=(AppClock), force=false|
		list.do(_.defer(delta, clock, force))
	}

	collapse {
		|clock, force, delay|
		list.do(_.collapse(clock, force, delay))
	}
}

Connection {
	classvar <collectionStack;
	classvar <tracing, <>traceAll=false;

	var <object, <dependant;

	var connected = false;
	var <traceConnection;

	*initClass {
		Class.initClassTree(MethodSlot);
		tracing = List();
	}

	*findReachableConnections {
		// This should operate as a *loose* leak checker. It visits known root-level objects that could contain
		// connections or temporary connection-related objects, and collects them. If connections have been properly
		// cleaned up, this should return an empty list. We don't bother drilling down into Connection-related
		// objects, since we basically only care if this list is empty or not.
		var foundObjects = List[];
		var toIterate = List();
		var hasIterated = IdentitySet();
		var itemTypes = [ViewActionUpdater, UpdateForwarder, UpdateTracer, UpdateChannel, UpdateBroadcaster, UpdateFilter, UpdateTransform, UpdateDispatcher, MethodSlot, SynthArgSlot, SynthMultiArgSlot, PeriodicUpdater];

		toIterate.addAll([
			Object.dependantsDictionary,
			Connection.tracing, Connection.collectionStack,
			UpdateDispatcher.dispatcherDict.keys
		].flatten);

		while { toIterate.notEmpty } {
			var iter = toIterate.pop();
			iter !? {
				toIterate.size.postln;
				0.0001.yield;
				hasIterated.add(iter);

				if (iter.isKindOf(Collection)) {
					var coll = iter;
					if (iter.isKindOf(Dictionary)) {
						coll = coll.keys.asArray ++ coll.values.asArray
					};
					coll.do {
						|item|
						if (hasIterated.includes(item).not) {
							toIterate.add(item);
						}
					}
				} {
					if (itemTypes.any({ |c| iter.isKindOf(c) })) {
						foundObjects.add(iter);
					}
				}
			}
		};

		^foundObjects
	}



	*traceWith {
		|func|
		var collected, wasTracingAll;
		wasTracingAll = traceAll;
		traceAll = true;

		protect({
			collected = Connection.collect(func);
		}, {
			if (wasTracingAll.not) {
				collected.list.do(_.trace(false));
			};
			traceAll = wasTracingAll;
		})
	}

	*prBeforeCollect {
		collectionStack = collectionStack.add(List(20));
	}

	*prAfterCollect {
		^ConnectionList(collectionStack.pop());
	}

	*basicNew {
		|object, dependant, connected|
		^super.newCopyArgs(object, dependant, connected).trace(traceAll).prCollect()
	}

	*new {
		|object, dependant, autoConnect=true|
		^super.newCopyArgs(object, dependant).connected_(autoConnect).trace(traceAll).prCollect()
	}

	*untraceAll {
		tracing.copy.do(_.trace(false));
	}

	prCollect {
		if (collectionStack.size > 0) {
			collectionStack.last.add(this);
		}
	}

	connected {
		traceConnection.notNil.if {
			^traceConnection.connected
		} {
			^object.dependants !? { |d| d.includes(dependant) } ?? false;
		}
	}

	connected_{
		|connect|
		if (traceConnection.isNil) {
			if (connect != this.connected) {
				connected = connect;
				if (connect) {
					object.addDependant(dependant);
				} {
					object.removeDependant(dependant);
				}
			}
		} {
			traceConnection.connected = connect;
		}
	}

	connect {
		this.connected_(true)
	}

	disconnect {
		this.connected_(false)
	}

	connectionFreed {
		this.free();
	}

	free {
		this.trace(false);
		this.disconnect();
		object.connectionFreed(this);
		object = dependant = nil;
	}

	disconnectWith {
		|func|
		var wasConnected = this.connected;

		this.disconnect();

		^func.protect({
			if (wasConnected) {
				this.connect();
			}
		});
	}

	onTrace {
		|obj, what ...values|
		var from, to, connectedSym;
		from = object.isKindOf(Connection).if({ object.dependant }, { object });
		to = dependant.isKindOf(UpdateTracer).if({ dependant.wrappedObject }, { dependant });
		connectedSym = this.connected.if("⋯", "⋰");

		"% %.signal(%) → %\t =[%]".format(
			connectedSym++connectedSym,
			from.connectionTraceString(what),
			"\\" ++ what,
			to.connectionTraceString(what),
			(values.collect(_.asCompileString)).join(","),
		).postln
	}

	trace {
		|shouldTrace=true|
		if (shouldTrace) {
			traceConnection ?? {
				traceConnection = UpdateTracer(object, dependant, this);
				object.addDependant(traceConnection);
				object.removeDependant(dependant);
				tracing.add(this);
			}
		} {
			traceConnection !? {
				var tempTrace = traceConnection;
				traceConnection = nil;
				object.removeDependant(tempTrace);
				this.connected = tempTrace.connected;
				tracing.remove(this);
			}
		}
	}

	traceWith {
		|func|
		var wasTracing = traceConnection.notNil;
		this.trace(true);
		protect(func, {
			this.trace(wasTracing);
		});
	}

	dependants {
		^dependant.dependants
	}

	addDependant {
		|dep|
		if (dependant.dependants.size == 0) {
			this.connect();
		};

		dependant.addDependant(dep);
	}

	removeDependant {
		|dep|
		dependant.removeDependant(dep);

		if (dependant.dependants.size == 0) {
			this.disconnect();
		}
	}

	releaseDependants {
		dependant.releaseDependants();
		this.disconnect();
	}

	connectTo {
		|nextDependant|
		^Connection(this, nextDependant);
	}

	chain {
		|newDependant|
		var wasTracing = traceConnection.notNil;

		// We want to insert newDependant in between our current object and dependant.
		// I.e.: this.object -> newDependant -> this.dependant
		// The current (this) connection will represent the [newDependant -> this.dependant]
		// portion, and we construct and return a new connection for [this.object -> newDependant].
		this.trace(false);
		this.disconnectWith({
			object = object.connectTo(newDependant);
		});
		this.trace(wasTracing);
	}

	filter {
		|filter|
		if (filter.isKindOf(Symbol)) {
			this.chain(UpdateKeyFilter(filter))
		} {
			this.chain(UpdateFilter(filter))
		}
	}

	transform {
		|func|
		this.chain(UpdateTransform(func))
	}

	defer {
		|delta=0, clock=(AppClock), force=false|
		this.chain(DeferredUpdater(delta, clock, force));
	}

	collapse {
		|delta=0, clock=(AppClock), force=true|
		this.chain(CollapsedUpdater(delta, clock, force))
	}

	oneShot {
		this.chain(OneShotUpdater(this));
	}
}

ViewActionUpdater {
	classvar funcs, onCloseFunc;

	*initClass {
		funcs = MultiLevelIdentityDictionary();
		onCloseFunc = {
			|view, actionName, func|
			ViewActionUpdater.disable(view, actionName, func);
		};
	}

	*actionFunc {
		|propertyName\value, signalName=\value|
		var func = funcs.at(propertyName, signalName);
		if (func.isNil) {
			func = "{ |view ...args| view.changed('%', view.%) }".format(signalName, propertyName).interpret;
			funcs.put(propertyName, signalName, func);
		};
		^func;
	}

	*isConnected {
		|view, actionName, actionFunc|
		var isConnected = false;
		isConnected == isConnected || (view.perform(actionName) == actionFunc);
		if (view.perform(actionName).isKindOf(FunctionList)) {
			isConnected = isConnected || view.perform(actionName).array.includes(actionFunc);
		};
		^isConnected;
	}

	*enable {
		|view, actionName=\action, propertyName=\value, signalName=\value|
		var func = this.actionFunc(propertyName, signalName);
		if (this.isConnected(view, actionName, func).not) {
			view.perform(actionName.asSetter, view.perform(actionName).addFunc(func));
			view.onClose = view.onClose.addFunc(onCloseFunc.value(_, actionName, func));
		}
	}

	*disable {
		|view, actionName, func|
		view.perform(actionName.asSetter, view.perform(actionName).removeFunc(func));
	}
}

UpdateForwarder {
	var <dependants;

	*new {
		^super.new.prInitDependants;
	}

	prInitDependants{
		dependants = IdentitySet();
	}

	changed { arg what ... moreArgs;
		dependants.do({ arg item;
			item.update(this, what, *moreArgs);
		});
	}

	addDependant { arg dependant;
		if (dependants.isNil) {
			dependants = IdentitySet();
			this.onDependantsNotEmpty
		};

		dependants.add(dependant);
	}

	removeDependant { arg dependant;
		dependants.remove(dependant);
		if (dependants.size == 0) {
			dependants = nil;
			this.onDependantsEmpty;
		};
	}

	release {
		this.releaseDependants();
	}

	releaseDependants {
		dependants.clear();
		this.onDependantsEmpty();
	}

	onDependantsEmpty {}

	onDependantsNotEmpty {}

	update {
		|object, what ...args|
		dependants.do {
			|item|
			item.update(object, what, *args);
		}
	}
}

UpdateTracer {
	var <upstream, <wrappedObject, <connection;
	var <>connected;

	*new {
		|upstream, wrappedObject, connection|
		^super.newCopyArgs(upstream, wrappedObject, connection).init
	}

	init {
		connected = upstream.dependants.includes(wrappedObject);
	}

	update {
		|object, what ...args|
		connection.onTrace(object, what, *args);
		if (connected) {
			wrappedObject.update(object, what, *args);
		}
	}

	addDependant {
		|dependant|
		wrappedObject.addDependant(dependant);
	}

	removeDependant {
		|dependant|
		wrappedObject.removeDependant(dependant);
	}

	release {
		wrappedObject.release();
	}

	releaseDependants {
		wrappedObject.releaseDependants();
	}
}

UpdateChannel : Singleton {
	update {
		|object, what ...args|
		this.changed(what, *args)
	}
}

UpdateBroadcaster : Singleton {
	// simply rebroadcast
	update {
		|object, what ...args|
		dependantsDictionary.at(this).copy.do({ arg item;
			item.update(object, what, *args);
		});
	}
}

UpdateFilter {
	var <func;

	*new {
		|func|
		^super.newCopyArgs(func)
	}

	update {
		|object, what ...args|
		if (func.value(object, what, *args)) {
			dependantsDictionary.at(this).copy.do({ arg item;
				item.update(object, what, *args);
			});
		}
	}
}

UpdateTransform {
	var <func;

	*new {
		|func|
		^super.newCopyArgs(func)
	}

	update {
		|object, what ...args|
		var argsArray = func.value(object, what, *args);
		if (argsArray.notNil) {
			argsArray = argsArray[0..1] ++ argsArray[2];
			dependantsDictionary.at(this).copy.do({ arg item;
				item.update(*argsArray);
			});
		}
	}
}

ConnectionMap : ConnectionList {
	*new {
		|...args|
		var dict = IdentityDictionary.newFrom(args);
		var transformer = UpdateTransform({
			|obj, changed ...args|
			var newKey = dict[obj];
			if (newKey.notNil) {
				[obj, newKey, args];
			} {
				nil;
			}
		});

		^super.newFrom(dict.keys.connectAll(transformer))
	}
}

UpdateKeyFilter : UpdateFilter {
	var <>key;

	*new {
		|key|
		var func = "{ |obj, inKey| % == inKey }".format("\\" ++ key).interpret;
		^super.new(func).key_(key);
	}

	connectionTraceString {
		^"%(%)".format(this.class, "\\" ++ key)
	}
}

UpdateDispatcher {
	classvar <dispatcherDict;

	var dispatchTable;
	var connection;

	*initClass {
		dispatcherDict = IdentityDictionary();
	}

	*new {
		|object|
		^dispatcherDict.atFail(object, {
			var newObj = super.new.init(object);
			dispatcherDict[object] = newObj;
			newObj;
		})
	}

	*clear {
		|object|
		dispatcherDict[object] !? { |d| d.clear };
	}

	init {
		|object|
		connection = object.connectTo(this);
		dispatchTable = IdentityDictionary();
	}

	at {
		|key|
		^dispatchTable.atFail(key, {
			// We want a connection to represent our dispatcher->(named signal)
			// but we want to manage ourselves - so no need to connect this.
			var connection = Connection.basicNew(this, UpdateForwarder(), true);
			dispatchTable[key] = connection;
			connection;
		})
	}

	remove {
		|key|
		dispatchTable.removeAt(key);
		if (dispatchTable.size == 0) {
			this.clear();
		}
	}

	dependants {
		^dispatchTable !? { dispatchTable.values.collect(_.dependant) } ?? []
	}

	update {
		|obj, changed ...args|
		dispatchTable[changed] !? {
			|connection|
			connection.dependant.update(obj, changed, *args);
		}
	}

	connectionCleared {
		|connection|
		dispatchTable.findKeyForValue(connection) !? (this.remove(_));
	}

	clear {
		dispatcherDict[connection.object] = nil;
		connection.clear;
		dispatchTable.values.do(_.releaseDependants);
		connection = dispatchTable = nil;
	}

	connectionTraceString {
		|what|
		^dispatchTable[what].dependant.connectionTraceString ?? { "%(%) - no target".format(this.class, this.identityHash) };
	}
}

MethodSlot {
	var <updateFunc, <reciever, <methodName;

	*new {
		|obj, method ...argOrder|
		^super.new.init(obj, method, argOrder)
	}

	init {
		|inObject, inMethodName, argOrder|
		reciever = inObject;
		methodName = inMethodName;
		updateFunc = MethodSlot.makeUpdateFunc(reciever, methodName, argOrder);
	}

	*makeUpdateFunc {
		|reciever, methodName, argOrder|
		var argString, callString;
		var possibleArgs = ['object', 'changed', '*args', 'args', 'value'];

		if (methodName.isKindOf(String) && argOrder.isEmpty) {
			// Should be of the form e.g. "someMethod(value, arg[0])"
			callString = methodName;
			methodName = methodName.split($()[0].asSymbol; // guess the method name - used later for validation
		} {
			argOrder.do {
				|a|
				if (a.isInteger.not && possibleArgs.includes(a).not) {
					Error("Can't handle arg '%' - must be one of: %.".format(a, possibleArgs.join(", "))).throw
				}
			};

			if (argOrder.isEmpty) {
				argOrder = ['object', 'changed', '*args'];
			};

			argString = argOrder.collect({
				|a|
				if (a.isInteger) {
					"args[%]".format(a)
				} {
					a.asString
				}
			}).join(", ");
			callString = "%(%)".format(methodName, argString);
		};

		if (reciever.respondsTo(methodName).not && reciever.tryPerform(\know).asBoolean.not) {
			Exception("Object of type % doesn't respond to %.".format(reciever.class, methodName)).throw;
		};

		^"{ |reciever, object, changed, args| var value = args[0]; reciever.% }".format(callString).interpret;
	}

	update {
		|object, changed ...args|
		updateFunc.value(reciever, object, changed, args);
	}

	connectionTraceString {
		|what|
		^"%(%(%).%)".format(this.class, reciever.class, reciever.identityHash, methodName)
	}
}

MethodSlotUI : MethodSlot {
	classvar deferList, deferFunc;

	*initClass {
		deferList = List();
	}

	*doDeferred {
		var tmpList = deferList;
		deferList = List(tmpList.size);
		deferFunc = nil;

		tmpList.do {
			|argsList|
			argsList[0].value(*argsList[1]);
		}
	}

	*deferUpdate {
		|updateFunc, args|
		deferList.add([updateFunc, args]);
		deferFunc ?? {
			deferFunc = { MethodSlotUI.doDeferred }.defer
		}
	}

	*prNew { ^super.prNew }

	update {
		|object, changed ...args|
		if (this.canCallOS) {
			updateFunc.value(reciever, object, changed, args);
		} {
			this.class.deferUpdate(updateFunc, [reciever, object, changed, args])
		}
	}
}

ValueSlot : MethodSlot {
	*new {
		|obj, setter=\value_|
		^super.new(obj, setter, \value)
	}
}

FunctionSlot : MethodSlot {
	*new {
		|obj ...argOrder|
		^super.new(obj, \value, *argOrder)
	}
}

SynthArgSlot {
	var <synth, <>argName, synthConn;

	*new {
		|synth, argName|
		^super.newCopyArgs(synth, argName).init
	}

	init {
		synth.register;
		synthConn = synth.connectTo(this.methodSlot(\free)).filter(\n_end);
	}

	free {
		synthConn.disconnect().clear();
		synth = argName = synthConn =nil;
	}

	update {
		|obj, what, value|
		if (synth.notNil) {
			synth.set(argName, value);
		}
	}
}

SynthMultiArgSlot {
	var <synth, <mapFunction, synthConn;

	*new {
		|synth ...argsMap|
		^super.newCopyArgs(synth).init(argsMap)
	}

	init {
		|argsMap|
		synth.register;
		synthConn = synth.connectTo(this.methodSlot(\free)).filter(\n_end);

		if (argsMap.size == 0) {
			mapFunction = {|k| k};
		} {
			if ((argsMap.size == 1) && argsMap[0].isFunction) {
				mapFunction = argsMap[0]
			} {
				argsMap = argsMap.copy.asDict;
				mapFunction = argsMap[_];
			}
		}
	}

	free {
		synthConn.disconnect().clear();
		synth = mapFunction = synthConn =nil;
	}

	update {
		|obj, what, value|
		var argName = mapFunction.value(what);
		if (argName.notNil) {
			synth.set(argName, value);
		}
	}
}

SynthValueMapSlot : SynthMultiArgSlot {
	update {
		|obj, what, value|
		if (what == \value) {
			var argName = mapFunction.value(obj);
			if (argName.notNil) {
				synth.set(argName, value);
			}
		}
	}
}

DeferredUpdater : UpdateForwarder {
	var clock, force, delta;

	*new {
		|delta=0, clock=(AppClock), force=true|
		^super.new.init(delta, clock, force)
	}

	init {
		|inDelta, inClock, inForce|
		clock = inClock;
		force = inForce;
		delta = inDelta;
	}

	update {
		|object, what ...args|
		if ((thisThread.clock == clock) && force.not) {
			super.update(object, what, *args);
		} {
			clock.sched(delta, {
				super.update(object, what, *args);
			})
		}
	}
}

OneShotUpdater : UpdateForwarder {
	var <>connection;

	*new {
		|connection|
		^super.new.connection_(connection)
	}

	update {
		|object, what ...args|
		protect {
			super.update(object, what, *args);
		} {
			connection.disconnect();
		}
	}
}

CollapsedUpdater : UpdateForwarder {
	var clock, force, delta;
	var deferredUpdate;
	var holdUpdates=false;

	*new {
		|delta=0, clock=(AppClock), force=true|
		^super.new.init(delta, clock, force)
	}

	init {
		|inDelta, inClock, inForce|
		clock = inClock;
		force = inForce;
		delta = inDelta;
	}

	deferIfNeeded {
		|func|
		if ((thisThread.clock == clock) && force.not) {
			func.value
		} {
			clock.sched(0, func);
		}
	}

	update {
		|object, what ...args|
		if (holdUpdates) {
			deferredUpdate = [object, what, args];
		} {
			holdUpdates = true;

			this.deferIfNeeded {
				super.update(object, what, *args);
			};

			clock.sched(delta, {
				holdUpdates = false;
				if (deferredUpdate.notNil) {
					var tmpdeferredUpdate = deferredUpdate;
					deferredUpdate = nil;
					this.update(tmpdeferredUpdate[0], tmpdeferredUpdate[1], *tmpdeferredUpdate[2]);
				};
			})
		}
	}
}

PeriodicUpdater {
	var <object, <method;
	var <freq, <>name;
	var <process, <lastVal;

	*new {
		|object, method=\value, freq=0.1|
		^super.newCopyArgs(object, method).freq_(freq).name_(method);
	}

	freq_{
		|inFreq|
		freq = inFreq;
		process.stop();
		process = SkipJack(this.pull(_), freq, name:"PeriodicUpdater_" ++ this.identityHash.asString);
	}

	start {
		process.start();
	}

	stop {
		process.stop();
	}

	pull {
		var val = object.perform(method);
		if (val != lastVal) {
			lastVal = val;
			this.changed(\value, val)
		};
	}
}

BusUpdater : PeriodicUpdater {
	*new {
		|bus, freq=0.1|
		^super.new(bus, \getSynchronous, freq);
	}
}

AbstractControlValue {
	classvar <>defaultValue, <>defaultSpec;
	var <value, <spec;

	*new {
		|initialValue, spec|
		^super.new.init(initialValue, spec);
	}

	init {
		|initialValue, inSpec|
		value = initialValue ?? defaultValue.copy;
		spec = inSpec ?? defaultSpec.copy;
	}

	value_{
		|inVal|

		inVal = spec.constrain(inVal);

		if (value != inVal) {
			value = inVal;
			this.changed(\value, value);
		}
	}

	input_{
		|inVal|
		this.value_(spec.map(inVal));
	}

	input {
		^spec.unmap(value);
	}

	spec_{
		|inSpec|

		if (inSpec != spec) {
			spec = inSpec;
			this.changed(\spec, spec);
			this.value = value;
		}
	}
}

NumericControlValue : AbstractControlValue {
	*initClass {
		Class.initClassTree(Spec);

		defaultValue = 0;
		defaultSpec = \unipolar.asSpec;
	}
}

MIDIControlValue : NumericControlValue {
	var midiFunc, func, isOwned=false;

	cc {
		| ccNumOrFunc, chan, srcID, argTemplate, dispatcher |
		func = func ? {
			|val|
			this.input = val / 127.0;
		};

		this.clearMIDIFunc();

		if (ccNumOrFunc.notNil) {
			if (ccNumOrFunc.isKindOf(MIDIFunc)) {
				isOwned = false;
				midiFunc = ccNumOrFunc;
				midiFunc.add(func);
			} {
				isOwned = true;
				midiFunc = MIDIFunc.cc(func, ccNumOrFunc, chan, srcID, argTemplate, dispatcher)
			}
		}
	}

	clearMIDIFunc {
		if (midiFunc.notNil) {
			midiFunc.remove(func);
			if (isOwned) {
				midiFunc.free;
			};
			midiFunc = nil;
		}
	}
}

+Object {
	valueSlot {
		|setter=\value_|
		^ValueSlot(this, setter)
	}

	methodSlot {
		|method ...argOrder|
		^MethodSlot(this, method, *argOrder)
	}

	connectTo {
		|...dependants|
		var autoConnect = if (dependants.last.isKindOf(Boolean)) { dependants.pop() } { true };
		if (dependants.size == 1) {
			^Connection(this, dependants[0], autoConnect);
		} {
			^ConnectionList(dependants.collect {
				|dependant|
				Connection(this, dependant, autoConnect)
			})
		}
	}

	mapToSlots {
		|...associations|
		^ConnectionList.makeWith {
			associations.do {
				|assoc|
				assoc.key.connectTo(this.methodSlot(assoc.value));
			}
		}
	}

	signal {
		|keyOrFunc|
		if (keyOrFunc.isNil) {
			^this
		} {
			if (keyOrFunc.isKindOf(Symbol)) {
				^UpdateDispatcher(this).at(keyOrFunc);
			} {
				^this.connectTo(UpdateFilter(keyOrFunc));
			}
		}
	}

	connectionTraceString {
		^"%(%)".format(this.class, this.identityHash)
	}

	connectionCleared {}
}

+Node {
	argSlot {
		|argName|
		^SynthArgSlot(this, argName)
	}

	mapToArgs {
		|...associations|
		^ConnectionList.makeWith {
			associations.do {
				|assoc|
				assoc.key.signal(\value).connectTo(this.argSlot(assoc.value));
			}
		}
	}
}

+Collection {
	connectAll {
		|dependant|
		^ConnectionList(this.collect(_.connectTo(dependant)))
	}

	connectMap {
		|dependant|
		var pairs = this.asPairs.clump(2).collect(_.reverse).flatten;
		^ConnectionMap(*pairs).connectTo(dependant);
	}
}

+View {
	updateOnAction {
		|should=true|
		if (should) {
			ViewActionUpdater.enable(this);
		} {
			ViewActionUpdater.disable(this);
		}
	}
}

+Dictionary {
	atCreate {
		|key, defaultFunc|
		var val = this.at(key);
		if (val.notNil) {
			^val
		} {
			var newVal = defaultFunc.value();
			this.put(key, newVal);
			^newVal;
		}
	}
}

