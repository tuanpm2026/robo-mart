export interface PaginationMeta {
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface PagedResponse<T> {
  data: T[]
  pagination: PaginationMeta
  traceId: string
}

export interface ApiResponse<T> {
  data: T
  traceId: string
}

export interface ProductListItem {
  id: number
  sku: string
  name: string
  price: number
  rating: number
  brand: string
  stockQuantity: number
  categoryName: string
  primaryImageUrl: string
}

export interface Category {
  id: number
  name: string
  description: string
}

export interface ProductImage {
  id: number
  imageUrl: string
  altText: string
  displayOrder: number
}

export interface ProductDetail {
  id: number
  sku: string
  name: string
  description: string
  price: number
  rating: number
  brand: string
  stockQuantity: number
  category: Category
  images: ProductImage[]
  createdAt: string
  updatedAt: string
}

export interface ProductSearchParams {
  keyword?: string
  minPrice?: number
  maxPrice?: number
  brand?: string
  minRating?: number
  categoryId?: number
  page?: number
  size?: number
}

export interface ProductListParams {
  categoryId?: number
  page?: number
  size?: number
}
