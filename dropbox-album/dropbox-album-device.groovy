// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1711
// hubitat end

/**
 *  Dropbox Album Device
 *
 *  This is a device handler for a Dropbox album that allows you to display images
 *  and control the slideshow functionality.
 *
*/
metadata {
    definition (name: "Dropbox Album Device", namespace: "jpage4500", author: "Joe Page") {
        capability "Image Capture"

        attribute "image", "string"
        attribute "lastUpdatedMs", "number"

        command 'next'
        command 'previous'
        command 'pause'
        command 'resume'
    }
}

def parse(String description) {
    // no parsing needed
}

def setImage(url) {
    sendEvent(name: "image", value: url)
    sendEvent(name: "lastUpdatedMs", value: now())
}

def next() {
    parent.showNextImage()
}

def prev() {
    parent.showPreviousImage()
}

def pause() {
}

def resume() {
}
