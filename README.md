# magic-sheet

Make all those Clojure[Script] repeating repl commands one keystroke away.

Connect to any nrepl server (lein repl, fighwheel nrepl, etc), define some commands and run them with a keystroke.

Lots of times when developing or dev testing Clojure[Script] applications I find myself at the repl firing a bunch of commands 
only with the purpose of checking or changing the state of the app. For example : querying some db tables, checking the state of the cache,
checking blockchain time or account balances, restaring your components, incrementing time, clearing your cache, etc.

If you are in the same situation, maybe magic-sheet can help you.

It works by creating a sheet where you can arrange commands you are runnig constantly, so you have them one keystroke away.

Take a look at the screenshot at the end to see a example.

## Prerequisites 

- Install JDK 8
- If you install OpenJDK make sure you install openjfx

## Building 

```bash
lein uberjar
```

## Basic usage

### Run it

You can connect magic-sheet to any nrepl server like :

```bash
java -jar target/uberjar/magic-sheet-0.1.0-standalone.jar --port 40338
```

After first run, you will end up with a empty sheet. 

for more options try  `--help`

### Define a new command

For defining a new command, right click anywhere on the sheet and you should see a context menu. Click on new.
This will open the create command dialog, which looks like:

<img src="/doc/create-command.png?raw=true"/>

Then : 

- Choose a title.
- Add your clojure code. (I recommend trying your commands in your prefered repl first until you are happy with it)
- Add a key (just type any character), so you can use the keyboard instead of the mouse to run the command.
- Choose a result type. 

You can leave result type empty if you are only interested in the side effect, and not in the return value (like clear your cache or inc a counter inside an atom).
Result as table can be used if you know your command result returns a collection of maps that can be visualized as a table.
Result as value will show you the return value pretty printed, using the standard clojure.pprint/pprint.

You can also parametrize commands using `${param-name}` placeholders like:

```clojure
(my-app.server.dev/increment-time ${time})
```

### Designing your sheet

You can drag and resize your command widgets as you like, so you can accomodate related functionality together.

### Running a command

You can run commands using your mouse or your keyboard. Click on the command widget title or hit the assigned key.
You will see it flashing green while it is running.

### Saving and restoring your sheet

Right click on the background and then `Save sheet`. This will save the current sheet inside magic-sheet.edn in your current folder.
Everytime you run magic-sheet it searches for that file inside current folder and initialize with it's content. 

### Other features

- Clicking on any table cell will automatically put the content in your clipboard.

## How does it looks?

<img src="/doc/sheet-sample.png?raw=true"/>
