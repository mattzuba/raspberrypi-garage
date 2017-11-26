/**
 *  Copyright 2015 SmartThings
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
 *  Garage Door Opener
 *
 *  Author: SmartThings
 */
definition(
    name: "Garage Door Opener",
    namespace: "smartgarage",
    author: "Matt Zuba",
    description: "Open and close your garage door the smart way.",
    category: "Convenience",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Meta/garage_outlet.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Meta/garage_outlet@2x.png",
    singleInstance: true
)

preferences {
  page(name: "pageDiscovery",     install: false, uninstall: true, content: "pageDiscovery", nextPage: "pageConfiguration" )
  page(name: "pageConfiguration", install: true,  uninstall: true, content: "pageConfiguration")
}

// Page : 2 : Discovery Page
def pageDiscovery() {
  //create accessToken
  if(!state.accessToken) { createAccessToken() }

  //This is a workaround to prevent page to refresh too fast.
  if(!state.pageConfigurationRefresh) { state.pageConfigurationRefresh = 2 }

  dynamicPage(name: "pageDiscovery", nextPage: "pageConfiguration", refreshInterval: state.pageConfigurationRefresh) {
    state.pageConfigurationRefresh =  state.pageConfigurationRefresh + 3
    discoverySubscription()
    discoverySearch()
    discoveryVerification()
    def garageDoors = pageDiscoveryGetGarageDoors()
    section("Please wait while we discover your devices") {
      input(
        name: "selectedGarageDoors",
        type: "enum",
        title: "Select devices (${garageDoors.size() ?: 0} found)",
        required: true,
        multiple: true,
        options: garageDoors,
        defaultValue: selectedGarageDoors
      )
    }
  }
}

Map pageDiscoveryGetGarageDoors() {
    def garageDoors = [:]
    
    def verifiedGarageDoors = getDevices().findAll{ it.value.verified == true }
    verifiedGarageDoors.each {
    	garageDoors[it.value.mac] = "garage-${it.value.mac[-6..-1]}"
    }
    
    return garageDoors
}

//Page : 3 : Configure sensors and alarms connected to the panel
def pageConfiguration() {
  // Get all selected devices
  def configuredGarageDoors = [] + getConfiguredDevices()

  dynamicPage(name: "pageConfiguration") {
    configuredGarageDoors.each { garageDoor ->
    
      section(hideable: true, "garage-${garageDoor.mac[-6..-1]}") {
      	input(
        	name: "${garageDoor.mac}_name",
            title: "Door Name",
            type: "string",
            defaultValue: "Garage Door",
        )
      }
    }
  }
}

def installed() {
	log.info "installed(): Installing SmartGarage SmartApp"
	initialize() 
}

def updated() {
    log.info "updated(): Updating SmartGarage SmartApp"
    initialize() 
}

def uninstalled() {
    log.info "uninstall(): Uninstalling SmartGarage SmartApp"
}

def initialize() {
    unschedule()
    discoverySubscription(true)  
	runEvery5Minutes(discoverySearch)
    childDeviceConfiguration()
    state.pageConfigurationRefresh = 2
}

// Retrieve selected device
def getConfiguredDevices() {
  getDevices().findAll {
    selectedGarageDoors?.contains(it.value.mac)
  }.collect {
    it.value
  }
}

// Retrieve devices saved in state
def getDevices() {
    if (!state.devices) { 
    	state.devices = [:]
    }
    
    return state.devices
}

// Device Discovery : Device Type
def discoveryDeviceType() {
  return "urn:schemas-upnp-org:device:SmartGarage:1"
}

// Device Discovery : Send M-Search to multicast
def discoverySearch() {
    sendHubCommand(new physicalgraph.device.HubAction("lan discovery ${discoveryDeviceType()}", physicalgraph.device.Protocol.LAN))
}

// Device Discovery : Subscribe to SSDP events
def discoverySubscription(force=false) {
  if (force) {
    unsubscribe()
    state.subscribe = false
  }
  
  if(!state.subscribe) {
    subscribe(location, "ssdpTerm.${discoveryDeviceType()}", discoverySearchHandler, [filterEvents:false])
    state.subscribe = true
  }
}

// Device Discovery : Handle search response
def discoverySearchHandler(evt) {
    def event = parseLanMessage(evt.description)
    event << ["hub":evt?.hubId]
    String ssdpUSN = event.ssdpUSN.toString()
    def devices = getDevices()

    if (devices[ssdpUSN]) {
        def d = devices[ssdpUSN]
        d.networkAddress = event.networkAddress
        d.deviceAddress = event.deviceAddress
        def child = getChildDevice(event.mac)
        if (child) {
        	child.sync(event.networkAddress, event.deviceAddress)
        }
        log.debug "Refreshed attributes of device $d and child $child"
    } else {
        devices[ssdpUSN] = event
        log.debug "Discovered new device $event"
        discoveryVerify(event)
    }
}

//Device Discovery : Verify search response by retrieving XML
def discoveryVerification() {
    getDevices().findAll { it?.value?.verified != true }.each {
        discoveryVerify(it.value)
    }
}

// Device Discovery : Verify a Device
def discoveryVerify(Map device) {
    log.debug "Verifying communication with device $device"
    String host = getDeviceIpAndPort(device)
    sendHubCommand(
        new physicalgraph.device.HubAction(
            """GET ${device.ssdpPath} HTTP/1.1\r\nHOST: ${host}\r\n\r\n""",
            physicalgraph.device.Protocol.LAN,
            host,
            [callback: discoveryVerificationHandler]
        )
    )
}

//Device Discovery : Handle verification response
def discoveryVerificationHandler(physicalgraph.device.HubResponse hubResponse) {
    def body = hubResponse.xml
    def devices = getDevices()
    def device = devices.find { it?.key?.contains(body?.device?.UDN?.text()) }
    if (device) {
        log.debug "Verification Success: $body"
        device.value << [
        	serialNumber: body.device.serialNumber.text(),
            verified: true
        ]
    }
}

def childDeviceConfiguration() {
	def configuredGarageDoors = getConfiguredDevices()
	configuredGarageDoors.each { door ->
    	def device = getChildDevices()?.find{ it.deviceNetworkId == door.mac }
        
        if (!device) {
        	log.debug "Creating a new device with DNI: ${door.mac}"
            device = addChildDevice("smartgarage", "SmartGarage Door", door.mac, door.hub, [
            	label: settings."${door.mac}_name",
                data: [
                	"mac": door.mac,
                    "ip": door.networkAddress,
                    "port": door.deviceAddress
                ]
            ])
        }
    }
}

private String getDeviceIpAndPort(device) {
  "${convertHexToIP(device.networkAddress)}:${convertHexToInt(device.deviceAddress)}"
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}
