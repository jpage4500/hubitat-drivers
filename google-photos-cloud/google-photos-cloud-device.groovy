/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** Google Photos Cloud Device **
 *
 * Child device for the "Google Photos Cloud" app. Each device maps to one app-created Google album and exposes an
 * `image` attribute (an <img>/<video> HTML snippet) to display on a Hubitat dashboard as an Attribute tile.
 *
 * All work lives in the parent app; commands here just delegate, passing this device's network id so the parent
 * knows which album to act on.
 *
 *  Changes:
 *  1.0.0 - 06/08/26 - initial version
 *
 * NOTE: based on dkilgore90's Google Photos Device.
 * ------------------------------------------------------------------------------------------------------------------------------
 **/

metadata {
    definition(name: 'Google Photos Cloud Device', namespace: 'jpage4500', author: 'Joe Page',
        importUrl: 'https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/google-photos-cloud/google-photos-cloud-device.groovy') {
        capability 'Refresh'

        attribute 'image', 'string'
        attribute 'mediaType', 'string'
        attribute 'index', 'number'
        attribute 'total', 'number'

        command 'next'
        command 'previous'
        command 'pause'
        command 'resume'
    }

    preferences {
        input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false
    }
}

private logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug "${device.label}: $msg"
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def uninstalled() {
    parent?.childRemoved(device.deviceNetworkId)
}

def initialize() {
    sendEvent(name: 'image', value: device.currentValue('image') ?: '<img src="" />')
    sendEvent(name: 'mediaType', value: device.currentValue('mediaType') ?: 'photo')
    sendEvent(name: 'index', value: device.currentValue('index') ?: 0)
    sendEvent(name: 'total', value: device.currentValue('total') ?: 0)
}

def refresh() {
    logDebug("refresh")
    parent.getNextPhoto(device.deviceNetworkId)
}

def next() {
    logDebug("next")
    parent.getNextPhoto(device.deviceNetworkId)
}

def previous() {
    logDebug("previous")
    parent.getPrevPhoto(device.deviceNetworkId)
}

def pause() {
    logDebug("pause")
    parent.pausePhotos(device.deviceNetworkId)
}

def resume() {
    logDebug("resume")
    parent.resumePhotos(device.deviceNetworkId)
}
