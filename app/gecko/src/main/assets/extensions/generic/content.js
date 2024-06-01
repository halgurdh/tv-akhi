console.log("TV Bro generic content extension loaded");

//prevent text selection for the whole page (run only once)
window.addEventListener('load', function () {
    //document.body.style.userSelect = "none";
    console.log("window.load executed");
});

const communicatePort = browser.runtime.connectNative("tvbro_content");

communicatePort.onMessage.addListener(message => {
    switch (message.action) {
        case "updateSelection": {
            let x = message.data.x;
            let y = message.data.y;
            let w = message.data.width;
            let h = message.data.height;
            let pageX = x * (window.innerWidth / window.visualViewport.scale / w) + window.visualViewport.offsetLeft;
            let pageY = y * (window.innerHeight / window.visualViewport.scale / h) + window.visualViewport.offsetTop;


            if (document.caretPositionFromPoint) {
                let caretPosition = document.caretPositionFromPoint(pageX, pageY);
                let node = caretPosition.offsetNode;
                let offset = caretPosition.offset;
                let selection = window.getSelection();
                let selectionStarted = selection.rangeCount !== 0;
                if (selectionStarted && selection.getRangeAt(0).startContainer.selectionStart === undefined &&
                    selection.getRangeAt(0).endContainer.selectionStart === undefined) {
                    //there are some other selections, not started by this extension, clear them
                    selectionStarted = false;
                    selection.removeAllRanges();
                }
                if (!selectionStarted) {
                    node.selectionStart = offset;
                    let range = document.createRange();
                    range.setStart(node, offset);
                    range.setEnd(node, offset);
                    selection.addRange(range);
                } else {
                    let range = selection.getRangeAt(0);
                    if (range.startContainer.selectionStart !== undefined && range.startContainer.selectionStart === range.startOffset) {
                        range.collapse(true);
                    } else {
                        range.collapse(false);
                    }

                    switch (range.comparePoint(node, offset)) {
                        case -1: {
                            range.setStart(node, offset);
                            break;
                        }
                        case 1: {
                            range.setEnd(node, offset);
                            break;
                        }
                    }

                    selection.removeAllRanges();
                    selection.addRange(range);
                }
            }
            break;
        }

        case "clearSelection": {
            let selection = window.getSelection();
            let range = selection.getRangeAt(0);
            delete range.startContainer.selectionStart;
            delete range.endContainer.selectionEnd;
            selection.removeAllRanges();
            break;
        }
    }
});