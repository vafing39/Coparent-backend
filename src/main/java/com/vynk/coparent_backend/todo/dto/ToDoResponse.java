package com.vynk.coparent_backend.todo.dto;

import java.util.List;

/** « À traiter » vu par un parent : ce qui attend sa réponse ({@code mine}) et ce qui attend l'autre ({@code theirs}). */
public record ToDoResponse(List<ToDoItemResponse> mine, List<ToDoItemResponse> theirs) {
}
