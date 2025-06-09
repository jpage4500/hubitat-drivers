/**
 *
 *  Copyright 2021 David Kilgore. All Rights Reserved
 *
 *  This software is free for Private Use. You may use and modify the software without distributing it.
 *  If you make a fork, and add new code, then you should create a pull request to add value, there is no
 *  guarantee that your pull request will be merged.
 *
 *  You may not grant a sublicense to modify and distribute this software to third parties without permission
 *  from the copyright holder
 *  Software is provided without warranty and your use of it is at your own risk.
 *
 *  version: 0.1.0
 */

metadata {
    definition(name: 'Google Photos Device', namespace: 'dkilgore90', author: 'David Kilgore', importUrl: 'https://raw.githubusercontent.com/dkilgore90/google-photos/master/photos-device.groovy') {
        capability 'Refresh'

        attribute 'image', 'string'
        attribute 'mediaType', 'string'

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
    unschedule()
}

def initialize() {
    device.sendEvent(name: 'image', value: device.currentValue('image') ?: '<img src="" />')
    device.sendEvent(name: 'mediaType', value: device.currentValue('mediaType') ?: 'image')
}

def refresh() {
    parent.getNextPhoto()
}

def next() {
    parent.getNextPhoto()
}

def previous() {
    parent.getPrevPhoto()
}

def pause() {
    parent.pausePhotos()
}

def resume() {
    parent.resume()
}
