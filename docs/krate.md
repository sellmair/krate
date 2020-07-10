# Module krate

Kotlin library for providing helpers for dealing with databases.

## Features

- `Entity` to `Table` binding
- Extensions for `exposed`'s tables
- Query Optimizer
- Default `Repository` implementation

## Installation

Simply include the library from jitpack

#### Sample  (Gradle Kotlin DSL) :

```kotlin
repositories {
    maven("https://jitpack.io")
}

/* ... */

dependencies {
    implementation("com.github.blogify-dev", "krate", "master-SNAPSHOT")
}

``` 

# Package krate.binding

Handles binding of `EntityTable`s with `Entity`s

## Example

A binding can be created as:

```kotlin
@SqlTable(People::class)
class Person(val name: String, uuid: UUID = UUID.randomUUID()) : Entity(uuid)

object People : EntityTable<Person>(Person::class, name = "people") {

    val name  = text ("name")
    val uuid  = uuid ("uuid")

    init {
        bind(uuid, Person::uuid)
        bind(name, Person::name)
    }

}
```

# Package krate.computed

Houses aggregating of computed properties

# Package krate.computed.extensions

Houses extensions for [krate.extensions](krate.extensions/index.html)

# Package krate.handling

Houses query handler functions

# Package krate.models

Model classes for dealing with repositories and tables

# Package krate.optimizer

Optimizes SQL queries to reduce the amount of queries executed on the database.  

