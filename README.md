# kotlin-shell

## Get
```kotlin
repository {
    maven(url = "http://dl.bintray.com/olegkrikun/maven")
}

dependency {
    implementation("ru.krikun.kotlin:kotlin-shell:0.0.1")
}
```

## Usage

```kotlin
shell(workingDir) {
    call("git clone git@github.com:OlegKrikun/kotlin-shell.git").execute()
    call("cd kotlin-shell").execute()
    call("ls").result { println(it) }
    
    // OR...

    "git clone git@github.com:OlegKrikun/kotlin-shell.git"()
    "cd kotlin-shell"()
    "ls" { println(it) }
}
```