# Module krate

Unopiniated and simple ORM for Kotlin using Exposed

## Features

- `Entity` to `Table` binding
- Extensions for `exposed`'s tables
- Query Optimizer
- Default `Repository` implementation

## Installation

Simply include the library from jitpack

#### Sample (Gradle Kotlin DSL) :

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

Handles binding of `EntityTable`s with entities

## Example

A binding can be created with:

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

Includes special computed property containers for entities 

# Package krate.computed.extensions

Extensions for [krate.computed](krate.computed/index.html)

# Package krate.handling

Houses query handler functions

# Package krate.models

Model classes for dealing with repositories and tables

# Package krate.optimizer

Optimizes entity queries on common operations to reduce the amount of effective SQL queries

