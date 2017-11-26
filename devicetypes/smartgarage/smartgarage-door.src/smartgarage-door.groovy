/**
 *  SmartGarage Door
 *
 *  Copyright 2017 Matt Zuba
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
	definition (name: "SmartGarage Door", namespace: "smartgarage", author: "Matt Zuba") {
    	capability "Actuator"
		capability "Garage Door Control"
        capability "Door Control"
        capability "Contact Sensor"
        capability "Momentary"
        capability "Switch"
        capability "Relay Switch"
        capability "Configuration"
        capability "Refresh"
        capability "Polling"
        
        attribute "cpu", "number"
        attribute "ram", "number"
        attribute "uptime", "string"
        attribute "timeElapsed", "string"
	}


	simulator {
		// TODO: define status and reply messages here
	}

	tiles(scale: 2) {
    	standardTile("door", "device.door", width:1, height:1, canChangeIcon: false) {
            state "unknown", label:'${name}', action:"refresh.refresh", icon:"st.doors.garage.garage-open", backgroundColor:"#cccccc"
            state "closed", label:'${name}', action:"door control.open", icon:"st.doors.garage.garage-closed", backgroundColor: '#00a0dc'
            state "open", label:'${name}', action:"door control.close", icon:"st.doors.garage.garage-open", backgroundColor:"#e86d13"
            state "opening", label:'${name}', icon:"st.doors.garage.garage-opening", backgroundColor:"#d04e00"
            state "closing", label:'${name}', icon:"st.doors.garage.garage-closing", backgroundColor:"#d04e00"
		}
    	multiAttributeTile(name:"detail", type:"generic", width:6, height:4, canChangeIcon: false) {
			tileAttribute("device.door", key: "PRIMARY_CONTROL") {
				attributeState "unknown", action:"refresh.refresh", icon:"st.doors.garage.garage-open", backgroundColor:"#cccccc"
				attributeState "closed", action:"door control.open", icon:"st.doors.garage.garage-closed", backgroundColor: '#00a0dc'
				attributeState "open", action:"door control.close", icon:"st.doors.garage.garage-open", backgroundColor:"#e86d13"
				attributeState "opening", icon:"st.doors.garage.garage-opening", backgroundColor:"#d04e00"
				attributeState "closing", icon:"st.doors.garage.garage-closing", backgroundColor:"#d04e00"
            }
			tileAttribute("device.timeElapsed", key: "SECONDARY_CONTROL") {
				attributeState 'timeElapsed', label: '${currentValue}', defaultState: true
            }
		}
		standardTile("open", "device.door", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "val", label:'', action:"door control.open", icon:"st.doors.garage.garage-opening", defaultState: true
		}
		standardTile("close", "device.door", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "val", label:'', action:"door control.close", icon:"st.doors.garage.garage-closing", defaultState: true
		}
		standardTile("refresh", "device.door", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "val", label:'', action:"refresh", icon:"st.secondary.refresh", defaultState: true
		}
        valueTile("cpu", "device.cpu", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
        	state "cpu", label:'CPU\n${currentValue}%', unit:"%", defaultState: true, backgroundColors: [
            	[value: 0, color:'#44b621'],
            	[value: 33, color:'#f1d801'],
            	[value: 67, color:'#d04e00'],
                [value: 100, color:'#bc2323']
            ]
        }
        valueTile("ram", "device.ram", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
        	state "ram", label:'RAM\n${currentValue}${unit}', unit:"MB", defaultState: true, backgroundColors: [
            	[value: 400, color:'#44b621'],
            	[value: 200, color:'#f1d801'],
            	[value: 100, color:'#d04e00'],
                [value: 0, color:'#bc2323']
            ]
        }
        valueTile("uptime", "device.uptime", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
        	state "uptime", label:'Up for ${currentValue}', defaultState: true
        }
        
		main "door"
		details(["detail","open","close","refresh", "cpu", "ram", "uptime"])
	}
}

def installed() {
	log.info "installed(): Installing SmartGarage Door Device"
    configure()
	initialize()
}

def updated() {
    log.info "updated(): Updating SmartGarage Door Device"
    initialize() 
}

def uninstalled() {
    log.info "uninstall(): Uninstalling SmartGarage Door Device"
}

def initialize() {
    unschedule()
	runEvery5Minutes(status)
}

// parse events into attributes
def parse(String description) {
	def msg = parseLanMessage(description)
    log.debug "Processing incoming message: ${msg.body}"
    
    def events = []
    def state = msg.data?.state
    if (state) {
    	log.debug "Updating state to '${state}'"
        
        events << createEvent(name: "door", value: state, descriptionText:"Door is ${state}")
        
        if (state == 'open' || state == 'opening'){
        	events << createEvent(name: "contact", value: "open", displayed: false)
            events << createEvent(name: "switch", value: "on", displayed: false)
        } else if (state == 'closed') {
        	events << createEvent(name: "contact", value: "closed", displayed: false)
            events << createEvent(name: "switch", value: "off", displayed: false)
        }
    }
    
    def cpu = msg.data?.cpu
    if (cpu) {
	    events << createEvent(name: "cpu", value: cpu, displayed: false)
    }

    def ram = msg.data?.ram?.free
    if (ram) {
	    events << createEvent(name: "ram", value: ram, displayed: false)
    }

    def uptime = msg.data?.uptime
    if (uptime) {
	    events << createEvent(name: "uptime", value: uptime, displayed: false)
    }

    def timeElapsed = msg.data?.timeElapsed
    if (timeElapsed) {
	    events << createEvent(name: "timeElapsed", value: timeElapsed, displayed: false)
    }

    return events
}

// Sends a REST call to the device so it can register the hub IP and port to send events too
def configure() {
	log.debug "Executing 'configure'"
    sendHubCommand(new physicalgraph.device.HubAction([
        method: "POST", 
        path: "/configure", 
        headers: [ HOST: getHostAddress() ],
        body : [
            hubAddress: getCallBackAddress()
        ]
    ]))
}

def status() {
	log.debug "Executing 'status'"
    sendHubCommand(new physicalgraph.device.HubAction([
        method: "GET", 
        path: "/status", 
        headers: [ HOST: getHostAddress() ]
    ]))
}

// handle commands
def open() {
	log.debug "Executing 'open'"
    sendHubCommand(new physicalgraph.device.HubAction([
        method: "GET", 
        path: "/open", 
        headers: [ HOST: getHostAddress() ]
    ]))
}

def close() {
	log.debug "Executing 'close'"
    sendHubCommand(new physicalgraph.device.HubAction([
        method: "GET", 
        path: "/close", 
        headers: [ HOST: getHostAddress() ]
    ]))
}

def push() {
    // get the current "door" attribute value
    def lastValue = device.latestValue("door");

    // if its open, then close the door
    if (lastValue == "open") {
        return close()

        // if its closed, then open the door
    } else if (lastValue == "closed") {
        return open()

    } else {
        log.debug "push() called when door state is $lastValue - there's nothing push() can do"
    }
}

def on() {
    log.debug "on() was called treat this like open()"
    open()
}

def off() {
    log.debug "off() was called treat like close()"
    close()
}

def refresh() {
	status()
}

def poll() {
	status()
}

def sync(ip, port) {
	def existingIp = getDataValue("ip")
	def existingPort = getDataValue("port")
	if (ip && ip != existingIp) {
		updateDataValue("ip", ip)
	}
	if (port && port != existingPort) {
		updateDataValue("port", port)
	}
    
    configure()
}

// gets the address of the Hub
private getCallBackAddress() {
    return device.hub.getDataValue("localIP") + ":" + device.hub.getDataValue("localSrvPortTCP")
}

// gets the address of the device
private getHostAddress() {
    def ip = getDataValue("ip")
    def port = getDataValue("port")

    if (!ip || !port) {
        def parts = device.deviceNetworkId.split(":")
        if (parts.length == 2) {
            ip = parts[0]
            port = parts[1]
        } else {
            log.warn "Can't figure out ip and port for device: ${device.id}"
        }
    }

    log.debug "Using IP $ip and port $port for device: ${device.id}"
    return convertHexToIP(ip) + ":" + convertHexToInt(port)
}

private Integer convertHexToInt(hex) {
    return Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
    return [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}